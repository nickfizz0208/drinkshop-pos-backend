"""Back up and apply migration 005 to the existing local POS database.

Requires the POS API to be stopped. No business rows are deleted.
Connection credentials are read from the ignored .pos-local.properties.
"""
from pathlib import Path
from decimal import Decimal
from datetime import datetime
import hashlib
import json
import os
import socket
import subprocess

ROOT=Path(__file__).resolve().parents[1]
SCHEMA="drinkshop_pos"
BIN=Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin")
local=dict(line.split("=",1) for line in (ROOT/".pos-local.properties").read_text().splitlines()
           if line and not line.startswith("#"))
env=os.environ.copy()
env["MYSQL_PWD"]=local["spring.datasource.password"]
connection=["--host=127.0.0.1","--port=3306","--user="+local["spring.datasource.username"]]


def sql(statement):
    result=subprocess.run([str(BIN/"mysql.exe"),*connection,"--default-character-set=utf8mb4","--batch","--raw","--skip-column-names"],
                          input=statement.encode(),env=env,capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout.decode().strip()


def snapshot(schema):
    """Compare every row while normalizing renamed columns and numeric scale."""
    tables=sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"' ORDER BY TABLE_NAME;").splitlines()
    result={}
    for table in tables:
        fields=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"+schema+"' AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION;").splitlines()
        fields=[name for name in fields if not (table=="order_item_topping" and name=="order_topping_id")]
        parts=[]
        for field in fields:
            key={"distance_m":"distance","duration_seconds":"duration"}.get(field,field)
            parts.append("'"+key+"',`"+field+"`")
        encoded=sql("SELECT JSON_OBJECT("+",".join(parts)+") FROM `"+schema+"`.`"+table+"`;")
        rows=[json.loads(row,parse_float=Decimal,parse_int=Decimal) for row in encoded.splitlines() if row]
        # Primary/foreign keys already uniquely order the baseline fixture rows.
        result[table]=sorted(rows,key=lambda row:tuple(str(row.get(k,"")) for k in sorted(row)))
    return result


with socket.socket() as sock:
    if sock.connect_ex(("127.0.0.1",8083))==0:
        raise RuntimeError("Stop the local POS API on 8083 before this migration.")

columns=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='drinkshop_pos' AND TABLE_NAME='pos_order' AND COLUMN_NAME IN ('distance','duration','distance_m','duration_seconds');").splitlines()
has_id=sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='drinkshop_pos' AND TABLE_NAME='order_item_topping' AND COLUMN_NAME='order_topping_id';")=="1"
money_columns={"product":["base_price","large_extra"],"topping":["price"],
               "daily_shift":["petty_cash","expected_cash","counted_cash"],
               "pos_order":["total","received_cash","change_cash","delivery_fee"],
               "order_item":["unit_price"],"order_item_topping":["price_at_order"]}
types=[]
for table,fields in money_columns.items():
    for field in fields:
        types.append(sql("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='drinkshop_pos' AND TABLE_NAME='"+table+"' AND COLUMN_NAME='"+field+"';"))
if set(columns)=={"distance","duration"} and has_id and all(t=="decimal(10,2)" for t in types):
    print("Migration 005 already applied; no changes made.")
    raise SystemExit(0)
if set(columns)!={"distance_m","duration_seconds"} or has_id or not all(t=="int" for t in types):
    raise RuntimeError("Unexpected or partial schema; stopped. Review the schema before continuing.")

for table,fields in money_columns.items():
    for field in fields:
        count=sql("SELECT COUNT(*) FROM drinkshop_pos.`"+table+"` WHERE `"+field+"`<0 OR `"+field+"`>99999999.99;")
        if count!="0":
            raise RuntimeError("Existing amount exceeds DECIMAL(10,2): "+table+"."+field)
if sql("SELECT COUNT(*) FROM drinkshop_pos.pos_order WHERE distance_m>99999999.99;")!="0":
    raise RuntimeError("Existing distance exceeds DECIMAL(10,2)")
checks=set(sql("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='drinkshop_pos' AND TABLE_NAME='pos_order' AND CONSTRAINT_TYPE='CHECK';").splitlines())
if not {"pos_order_chk_2","pos_order_chk_3"}.issubset(checks):
    raise RuntimeError("Expected baseline distance/time checks are missing; stopped.")

before=snapshot(SCHEMA)
quiz_before=snapshot("quiz_1150720")
folder=ROOT/"build/db-backups"
folder.mkdir(parents=True,exist_ok=True)
backup=folder/("drinkshop_pos_before_005_"+datetime.now().strftime("%Y%m%d_%H%M%S_%f")+".sql")
with backup.open("xb") as stream:
    dumped=subprocess.run([str(BIN/"mysqldump.exe"),*connection,"--default-character-set=utf8mb4",
                           "--single-transaction","--no-tablespaces","--column-statistics=0","--databases",SCHEMA],
                          stdout=stream,stderr=subprocess.PIPE,env=env)
if dumped.returncode or backup.stat().st_size==0:
    raise RuntimeError("Backup failed; migration not started.")
sql((ROOT/"database/005_team_er_alignment.sql").read_text(encoding="utf-8"))
after=snapshot(SCHEMA)
assert before==after,"Business data differs; inspect the backup before taking further action."
assert quiz_before==snapshot("quiz_1150720"),"Quiz database changed during migration."
assert len(after)==10
print("Migration 005 applied; all business rows preserved across 10 tables.")
print("Old quiz database snapshot unchanged.")
print("Backup:",backup)
report={"success":True,"tables":10,"businessRowsPreserved":True,"quizUnchanged":True,
        "backup":str(backup),"backupSha256":hashlib.sha256(backup.read_bytes()).hexdigest()}
(ROOT/"build/team-er-migration.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
