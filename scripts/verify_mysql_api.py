"""Run real HTTP/MySQL checks in a NEW disposable database, never the POS data.

Requires .pos-local.properties, built bootJar, local MySQL client, Java 21,
and Pillow (available in the bundled Codex Python runtime).
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import base64
import io
import json
import os
import re
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MYSQL = Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
JAVA = Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe")
SCHEMA = "drinkshop_api_check_" + secrets.token_hex(6)
assert re.fullmatch(r"drinkshop_api_check_[a-f0-9]{12}", SCHEMA)
local = dict(line.split("=", 1) for line in (ROOT / ".pos-local.properties").read_text().splitlines()
             if line and not line.startswith("#"))
env = os.environ.copy()
env["MYSQL_PWD"] = local["spring.datasource.password"]
created = False
process = None
checks = []


def sql(statement):
    result = subprocess.run([str(MYSQL), "--host=127.0.0.1", "--port=3306",
                             "--user=" + local["spring.datasource.username"],
                             "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"],
                            input=statement.encode(), env=env, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout.decode().strip()


def db(statement):
    return sql("USE `" + SCHEMA + "`; " + statement)


def snapshot(schema):
    tables = sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "' ORDER BY TABLE_NAME;").splitlines()
    return {table: sql("SELECT COUNT(*) FROM `" + schema + "`.`" + table.replace("`", "``") + "`;") for table in tables}


def api(path, payload=None, expected=200, raw=None, content_type=None, authenticated=True):
    headers = {}
    if authenticated:
        token = base64.b64encode(("staff:" + local["pos.demo.password"]).encode()).decode()
        headers["Authorization"] = "Basic " + token
    data = raw
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(base_url + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    assert status == expected, (path, status, body.decode(errors="replace"))
    if body.startswith(b"\x89PNG"):
        return body
    return json.loads(body, parse_float=Decimal)


def request(product=1, quantity=1, toppings=None):
    return {"requestId": str(uuid.uuid4()), "source": "TAKE_OUT", "payment": "LINE_PAY",
            "items": [{"productId": product, "size": "M", "temperature": "COLD",
                       "sugar": "HALF", "ice": "LESS", "qty": quantity,
                       "toppingIds": toppings or []}]}


before = {name: snapshot(name) for name in ("drinkshop_pos",)}
try:
    assert sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + SCHEMA + "';") == "0"
    sql((ROOT / "database/001_schema.sql").read_text(encoding="utf-8").replace("drinkshop_pos", SCHEMA))
    created = True
    for filename in ("002_demo_data.sql", "004_pos_api.sql", "005_team_er_alignment.sql", "006_demo_ingredient_costs.sql"):
        sql((ROOT / "database" / filename).read_text(encoding="utf-8").replace("drinkshop_pos", SCHEMA))
    cash_check=db("SELECT c.CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA='"+SCHEMA+"' AND t.TABLE_NAME='pos_order' AND c.CHECK_CLAUSE LIKE '%received_cash%'")
    assert re.fullmatch(r'[A-Za-z0-9_]+',cash_check)
    db('ALTER TABLE pos_order DROP CHECK `'+cash_check+'`;')
    db((ROOT/'database/007_payment_and_wastage.sql').read_text(encoding='utf-8'))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base_url = "http://127.0.0.1:" + str(port)
    env.update({"SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:3306/" + SCHEMA + "?serverTimezone=Asia/Taipei&characterEncoding=UTF-8",
                "SPRING_DATASOURCE_USERNAME": local["spring.datasource.username"],
                "SPRING_DATASOURCE_PASSWORD": local["spring.datasource.password"],
                "POS_DEMO_PASSWORD": local["pos.demo.password"]})
    output = ROOT / "build/mysql-api-check"
    output.mkdir(parents=True, exist_ok=True)
    with (output / "server.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([str(JAVA), "-jar", str(ROOT / "build/libs/drinkshop-pos-backend-0.0.1-SNAPSHOT.jar"),
                                    "--server.port=" + str(port), "--spring.config.import=", "--pos.demo.enabled=true",
                                    "--pos.photo-directory=" + str(output / "photos")],
                                   env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    deadline = time.monotonic() + 45
    while True:
        if process.poll() is not None:
            raise RuntimeError("Test server stopped; inspect build/mysql-api-check/server.log")
        try:
            api("/api/health", authenticated=False)
            break
        except (OSError, AssertionError):
            if time.monotonic() > deadline:
                raise RuntimeError("Test server startup timed out")
            time.sleep(.3)

    api("/api/menu", authenticated=False, expected=401)
    menu = api("/api/menu")
    assert len(menu["products"]) == 5 and "recipe" not in str(menu)
    assert isinstance(api("/api/rules")[0]["condition_json"], dict)
    checks.append("real MySQL authentication, menu privacy and rule JSON")

    before_cups = float(db("SELECT book_qty FROM inventory WHERE inventory_id=1;"))
    payload = request(quantity=2, toppings=[1])
    order = api("/api/orders", payload)
    assert order["total"] == 70 and order["items"][0]["unit_price"] == 35
    assert float(db("SELECT book_qty FROM inventory WHERE inventory_id=1;")) == before_cups - 2
    assert api("/api/orders", payload)["order_id"] == order["order_id"]
    assert float(db("SELECT book_qty FROM inventory WHERE inventory_id=1;")) == before_cups - 2
    db("UPDATE product SET recipe_json='{}' WHERE product_id=1;")
    path = "/api/orders/{}/items/{}".format(order["order_id"], order["items"][0]["item_id"])
    api(path + "/cancel", {"reason": "MySQL snapshot restoration test"})
    api(path + "/cancel", {"reason": "retry"})
    assert float(db("SELECT book_qty FROM inventory WHERE inventory_id=1;")) == before_cups
    checks.append("grouped-cup topping pricing, idempotency and exact one-time cancellation restoration")

    order = api("/api/orders", request(product=3))
    path = "/api/orders/{}/items/{}".format(order["order_id"], order["items"][0]["item_id"])
    api(path + "/status", {"status": "MAKING"})
    remaining = db("SELECT book_qty FROM inventory WHERE inventory_id=1;")
    api(path + "/cancel", {"reason": "made", "refundAmount": 0})
    assert db("SELECT book_qty FROM inventory WHERE inventory_id=1;") == remaining
    boundary = "PosPhotoCheck"
    buffer = io.BytesIO()
    Image.new("RGB", (2, 2), "white").save(buffer, format="PNG")
    body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n').encode() + buffer.getvalue() + ("\r\n--" + boundary + "--\r\n").encode()
    api(path + "/photo", raw=body, content_type="multipart/form-data; boundary=" + boundary)
    assert api(path + "/photo").startswith(b"\x89PNG")
    api(path + "/photo", raw=body, content_type="multipart/form-data; boundary=" + boundary, expected=409)
    checks.append("signed-in employee wastage without PIN or restoration, protected photo upload/read")

    db("UPDATE product SET base_price=25.15,large_extra=5.05 WHERE product_id=2;")
    db("UPDATE topping SET price=10.10 WHERE topping_id=1;")
    fractional=request(product=2, quantity=3, toppings=[1])
    fractional.update(payment="CASH", receivedCash=200.25, distance=1234.50, duration=180)
    fractional["items"][0]["size"]="L"
    result=api("/api/orders",fractional)
    assert result["total"]==Decimal("120.90") and result["change_cash"]==Decimal("79.35")
    assert result["distance"]==Decimal("1234.50") and result["duration"]==180
    assert "distance_m" not in result and "duration_seconds" not in result
    assert result["items"][0]["toppings"][0]["order_topping_id"]>0
    assert api("/api/orders",fractional)["order_id"]==result["order_id"]
    path="/api/orders/{}/items/{}".format(result["order_id"],result["items"][0]["item_id"])
    refunded=api(path+"/cancel",{"reason":"fractional cash refund check"})
    assert refunded["total"]==0 and refunded["change_cash"]==Decimal("200.25")
    checks.append("DECIMAL prices, exact fractional cash/refund, renamed distance/time and generated topping ID")

    unpaid=request(product=3,quantity=2,toppings=[2])
    unpaid.update(payment=None,unpaid=True)
    result=api('/api/orders',unpaid)
    assert result['payment_status']=='UNPAID' and result['paid_amount']==0
    assert result['items'][0]['material_cost']==Decimal('24.60')
    path='/api/orders/{}/items/{}'.format(result['order_id'],result['items'][0]['item_id'])
    api(path+'/status',{'status':'MAKING'})
    stock_before=db('SELECT book_qty FROM inventory WHERE inventory_id=1;')
    cancelled=api(path+'/cancel',{'reason':'unpaid no-show','refundAmount':0})
    assert cancelled['refunded_amount']==0 and cancelled['items'][0]['status']=='WASTED'
    assert stock_before==db('SELECT book_qty FROM inventory WHERE inventory_id=1;')
    another=request(product=3);another.update(payment=None,unpaid=True)
    result=api('/api/orders',another)
    path='/api/orders/{}/collect'.format(result['order_id'])
    for retry in range(2):
        collected=api(path,{'payment':'CASH','receivedCash':100})
        assert collected['paid_amount']==40 and collected['change_cash']==60
    cancelled=api('/api/orders/{}/cancel'.format(result['order_id']),{'reason':'partial refund','refundAmount':10.25})
    assert cancelled['refunded_amount']==Decimal('10.25') and cancelled['change_cash']==Decimal('70.25')
    checks.append('unpaid no-show cost snapshot, no automatic refund, collection retry and exact partial refund')

    batch=request(product=3,quantity=2)
    batch['items'].append(request(product=2)['items'][0])
    order=api('/api/orders',batch)
    batch_path='/api/orders/{}/status'.format(order['order_id'])
    api(batch_path,{'status':'PICKED_UP'},expected=409)
    for next_status in ('MAKING','DONE','PICKED_UP'):
        for retry in range(2):
            changed=api(batch_path,{'status':next_status})
            assert all(item['status']==next_status for item in changed['items'])
    checks.append('atomic whole-order production and pickup, premature pickup rejection and safe retries')

    db("UPDATE inventory SET book_qty=1 WHERE inventory_id=1;")
    def checkout(_):
        try:
            api("/api/orders", request(product=2))
            return 200
        except AssertionError as error:
            assert error.args[0][1] == 409
            return 409
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(checkout, range(2))) == [200, 409]
    assert float(db("SELECT book_qty FROM inventory WHERE inventory_id=1;")) == 0
    checks.append("real MySQL concurrent checkout cannot oversell")
    assert before == {name: snapshot(name) for name in before}
    checks.append("original POS table counts unchanged")
    report = {"success": True, "checks": checks, "isolatedDatabase": SCHEMA}
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    if created:
        # Only the exact random database successfully created by this run.
        assert re.fullmatch(r"drinkshop_api_check_[a-f0-9]{12}", SCHEMA)
        sql("DROP DATABASE `" + SCHEMA + "`;")
