# 收款、退款與原料損耗（007）

這份說明取代早期「取消一定全額退款」的約定。仍使用原本 10 張表。

## 操作

- 接單可選「現在收款」或「先接單，稍後收款」。未付款單照常預扣原料。
- 未付款單在訂單管理收款；沒有收款不能標記已取餐。
- 取消時付款狀態來自伺服器。未付款：退款固定為零。已付款：可不退款、部分退款或全退；上限為本次取消品項金額與尚未退還收款的較小值。
- 尚未製作取消：回補原扣料量，損耗零。已製作取消：由有效登入員工操作，轉 WASTED，不再扣料、不回補。
- 取消／退款目前為一次取消操作的一部分，無獨立補退介面；每筆明細所有杯數一起取消，已取餐與已日結訂單不在範圍。
- 店員確認人工收退款，系統記錄結果，未串金流。

## 欄位約定

| 欄位 | 含義 |
| --- | --- |
| pos_order.total | 保留品項合計；不代表收入，也不代表虧損 |
| payment_status | UNPAID 尚未收款，PAID 曾經收款；全退仍保留 PAID 與退款額 |
| payment | 未付款為 NULL；收款時記錄 CASH／CREDIT_CARD／LINE_PAY |
| paid_amount | 實際收取的訂單款項（已扣原找零），不因取消更改 |
| refunded_amount | 累計人工退款；淨收款 = paid_amount − refunded_amount |
| received_cash | 現金交付金額；例如百元付 25 元飲料，此欄為 100 |
| change_cash | 維持相容：原找零＋累計現金退款；原找零 = received_cash − paid_amount |
| order_item.refund_amount | 本次取消分配至各明細的退款額，整單依 item_id 分配，總和等於退款 |
| order_item.material_cost | 整筆明細所有杯數（含加料與杯材）的成本估算快照；NULL 為未知 |

material_cost 依當次扣料量乘原料 unit_cost，合計後四捨五入至分。不隨日後配方或成本變更重算。只有 WASTED 計入原料損耗；不把未收取售價再加到成本上。此為虛構材料估算，不含完整營運成本，不是淨損益。

舊單依舊 API 的「已收款且取消全退」行為補上收退款額，保留現金、庫存、狀態與訂單資料。舊成本不回填，因為沒有當時成本證據。

## API

建立未付款單：原 POST /api/orders 加上 `"unpaid":true`、`"payment":null`，不可帶 receivedCash。其他 items 等欄位不變。未帶 unpaid 預設已付款，保留舊送單 UUID 雜湊相容。

收款 POST `/api/orders/{id}/collect`：

```json
{"payment":"CASH","receivedCash":100}
```

收完整保留品項金額；相同付款方式和現金重試回同一單，不重複收款；不同內容回 409。已取消單不能收款。

原取消 API 增加 refundAmount，前端一律明確傳送：

```json
{"reason":"顧客未取餐","refundAmount":0}
```

如需退款則填正數，最多兩位小數。為相容舊客戶端，省略 refundAmount 時仍採當次可退上限；新程式不可省略。重複取消已處理明細不再退款或回補。收款、取消、扣料及退款紀錄使用同一營業日鎖定順序及資料庫交易。

## 遷移與測試

先完成 001、002、004、005、006；停止 API 後執行 `scripts/migrate_payment.py`。腳本只處理 drinkshop_pos，先 mysqldump 備份至忽略的 build/db-backups，再移除舊現金約束並執行 007。比對筆數及庫存，遇到已套用／非預期狀態會停止。不要直接重跑或重建既有表。

無法執行 Python 腳本時，可使用 `database/007_payment_and_wastage_workbench.sql`。共用 Aiven 已升級，不應執行；新庫或經團隊同意的舊庫才使用，先備份並停止所有後端。此檔需要 CREATE/ALTER ROUTINE 權限，所有業務變更包在單次程序呼叫內：已有五個新欄位及兩個約束則跳過，部分升級則在改動前報錯停止，即使客戶端繼續執行也不會落入後續 UPDATE。MySQL DDL 不支援整批回滾，中途失敗須人工檢查／恢復，不能忽略錯誤再跑。

36 項後端整合測試、39 項前端測試，以及隔離 MySQL 真實 HTTP 檢查涵蓋未收款棄單、部分退款、不退款、成本快照、重試、登入權限與扣料交易。

團隊暫不使用店長帳號／PIN 授權。已製作棄單 approved_by 存實際登入員工，舊欄位名稱保留以相容 schema；資料庫不因此新增或移除欄位。
