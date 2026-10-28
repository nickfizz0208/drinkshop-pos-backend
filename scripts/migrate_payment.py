"""Backup and migrate only drinkshop_pos; API must be stopped. No row deletion."""
from pathlib import Path
from datetime import datetime
import os, socket, subprocess
ROOT=Path(__file__).resolve().parents[1]
BIN=Path(r'C:\Program Files\MySQL\MySQL Server 8.0\bin')
local=dict(line.split('=',1) for line in (ROOT/'.pos-local.properties').read_text().splitlines() if line and not line.startswith('#'))
env=os.environ.copy(); env['MYSQL_PWD']=local['spring.datasource.password']
args=['--host=127.0.0.1','--user='+local['spring.datasource.username'],'--default-character-set=utf8mb4']
def sql(text):
    p=subprocess.run([str(BIN/'mysql.exe'),*args,'--batch','--raw','--skip-column-names','drinkshop_pos'],input=text.encode(),env=env,capture_output=True)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout.decode().strip()
with socket.socket() as s:
    if s.connect_ex(('127.0.0.1',8083))==0: raise RuntimeError('Stop API on 8083 before migrating.')
if sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='drinkshop_pos' AND TABLE_NAME='pos_order' AND COLUMN_NAME='payment_status'")!='0':
    raise RuntimeError('Payment migration already present; inspect before rerunning.')
backup=ROOT/'build/db-backups'/('before-payment-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.sql')
backup.parent.mkdir(parents=True,exist_ok=True)
with backup.open('wb') as out:
    subprocess.run([str(BIN/'mysqldump.exe'),*args,'--single-transaction','--no-tablespaces','drinkshop_pos'],env=env,stdout=out,check=True)
# Locate the original cash constraint by its expression, not its auto-generated name.
checks=sql("SELECT c.CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA='drinkshop_pos' AND t.TABLE_NAME='pos_order' AND c.CHECK_CLAUSE LIKE '%received_cash%'").splitlines()
if len(checks)!=1 or not checks[0].replace('_','').isalnum(): raise RuntimeError('Unexpected cash constraint; backup saved, no migration applied.')
before=sql('SELECT COUNT(*) FROM pos_order; SELECT COUNT(*) FROM order_item; SELECT GROUP_CONCAT(book_qty ORDER BY inventory_id) FROM inventory;')
sql('ALTER TABLE pos_order DROP CHECK `'+checks[0]+'`;\n'+(ROOT/'database/007_payment_and_wastage.sql').read_text(encoding='utf-8'))
after=sql('SELECT COUNT(*) FROM pos_order; SELECT COUNT(*) FROM order_item; SELECT GROUP_CONCAT(book_qty ORDER BY inventory_id) FROM inventory;')
assert before==after, 'Row counts or stock unexpectedly changed; review backup.'
print('Migration 007 applied. Existing rows and stock preserved. Backup:',backup)
