"""Local-only regression test for 007. Never reads the active Aiven configuration.

Usage: python scripts/test_workbench_migration.py --local-config PATH
PATH must contain spring.datasource.username/password for LOCAL MySQL.
Only random schemas created by this run are removed afterwards.
"""
from pathlib import Path
import argparse,json,os,re,secrets,subprocess

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--local-config',required=True,type=Path)
options=parser.parse_args()
cfg=dict(line.split('=',1) for line in options.local_config.read_text(encoding='utf-8-sig').splitlines() if line and not line.startswith('#'))
if 'spring.datasource.username' not in cfg or 'spring.datasource.password' not in cfg:
    raise SystemExit('Requires LOCAL spring.datasource.username/password; do not supply Aiven POS_DB_* config.')
env=os.environ.copy();env['MYSQL_PWD']=cfg['spring.datasource.password']
cli=[r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe','--host=127.0.0.1','--port=3306',
     '--user='+cfg['spring.datasource.username'],'--default-character-set=utf8mb4','--batch','--raw','--skip-column-names']
def sql(text,force=False,check=True):
    p=subprocess.run(cli+(['--force'] if force else []),input=text.encode(),env=env,capture_output=True)
    if check and (p.returncode or b'ERROR ' in p.stderr):
        raise RuntimeError(p.stderr.decode(errors='replace'))
    return p
def db(name,text,**kwargs): return sql('USE `'+name+'`;\n'+text,**kwargs)
def snapshot(name):
    tables=db(name,"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME;").stdout.decode().splitlines()
    result={}
    for table in tables:
        assert re.fullmatch(r'[a-z_]+',table)
        columns=db(name,"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION;").stdout.decode().splitlines()
        parts=','.join("'"+c+"',`"+c+"`" for c in columns)
        result[table]=sorted(db(name,'SELECT JSON_OBJECT('+parts+') FROM `'+table+'`;').stdout.decode().splitlines())
    result['schema']=db(name,'SHOW CREATE TABLE pos_order; SHOW CREATE TABLE order_item;').stdout.decode()
    return result
def install_base(name):
    assert sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+name+"';").stdout.strip()==b'0'
    baseline=(ROOT/'database/001_schema.sql').read_text(encoding='utf-8').replace('drinkshop_pos',name)
    create=[line for line in baseline.splitlines() if line.startswith('CREATE DATABASE ')]
    assert len(create)==1
    sql(create[0]);schemas.append(name)  # Cleanup only after this run successfully created the schema.
    sql(baseline.replace(create[0],'',1))
    for file in ('002_demo_data.sql','004_pos_api.sql','005_team_er_alignment.sql','006_demo_ingredient_costs.sql'):
        sql((ROOT/'database'/file).read_text(encoding='utf-8').replace('drinkshop_pos',name))
def migrate(name,force=False,check=True):
    migration=(ROOT/'database/007_payment_and_wastage_workbench.sql').read_text(encoding='utf-8').replace('drinkshop_pos',name)
    return db(name,'SET SESSION SQL_SAFE_UPDATES=1;\n'+migration+'\nSELECT @@SESSION.SQL_SAFE_UPDATES;',force=force,check=check)

schemas=[];report={'localOnly':True,'checks':[]}
try:
    name='drinkshop_migration_check_'+secrets.token_hex(6)
    install_base(name)
    installed=migrate(name)
    assert b'007 applied successfully.' in installed.stdout and installed.stdout.strip().endswith(b'1')
    report['checks'].append('first upgrade succeeds and restores SQL_SAFE_UPDATES')
    db(name,"""INSERT INTO pos_order(order_no,business_date,source,payment,total,status,created_by,payment_status,paid_amount,refunded_amount)
      VALUES ('PARTIAL-REFUND','2026-10-28','TAKE_OUT','LINE_PAY',0,'CANCELLED',1,'PAID',25,10);
      INSERT INTO order_item(order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,reason,refund_amount,material_cost)
      VALUES (LAST_INSERT_ID(),1,'local test','M','COLD','HALF','LESS',1,25,'CANCELLED','local test',10,3.8);
      INSERT INTO pos_order(order_no,business_date,source,payment,total,status,created_by,payment_status,paid_amount,refunded_amount)
      VALUES ('UNPAID','2026-10-28','TAKE_OUT',NULL,25,'PENDING',1,'UNPAID',0,0);
      INSERT INTO order_item(order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,material_cost)
      VALUES (LAST_INSERT_ID(),1,'local test','M','COLD','HALF','LESS',1,25,'PENDING',3.8);
      INSERT INTO pos_order(order_no,business_date,source,payment,total,status,created_by,payment_status,paid_amount,refunded_amount)
      VALUES ('NO-REFUND','2026-10-28','TAKE_OUT','LINE_PAY',0,'CANCELLED',1,'PAID',25,0);
      INSERT INTO order_item(order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,reason,approved_by,refund_amount,material_cost)
      VALUES (LAST_INSERT_ID(),1,'local test','M','COLD','HALF','LESS',1,25,'WASTED','local test',1,0,3.8);
      """)
    before=snapshot(name)
    for force in (False,True):
        repeated=migrate(name,force=force)
        assert b'already applied: skipped' in repeated.stdout and repeated.stdout.strip().endswith(b'1')
        assert snapshot(name)==before
    report['checks'].append('normal and continue-on-error retries preserve every business row and table definition')
    report['checks'].append('partial refund remains 10; unpaid remains 0 received; no-refund remains 0 refunded')
    partial='drinkshop_migration_check_'+secrets.token_hex(6)
    install_base(partial)
    db(partial,"ALTER TABLE pos_order ADD payment_status VARCHAR(10) DEFAULT 'PAID';")
    before_partial=snapshot(partial)
    stopped=migrate(partial,force=True,check=False)
    assert b'Partial 007 schema' in stopped.stderr and b'45000' in stopped.stderr
    assert stopped.stdout.strip().endswith(b'1') and snapshot(partial)==before_partial
    report['checks'].append('partial upgrade stops before changes even when client ignores errors')
finally:
    for name in schemas:
        assert re.fullmatch(r'drinkshop_migration_check_[a-f0-9]{12}',name)
        sql('DROP DATABASE IF EXISTS `'+name+'`;')
report['testSchemasRemoved']=True
report['success']=True
(ROOT/'build/workbench-migration-regression.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
