# 日結與報廢修正說明

## 行為約定

- 應有現金 = 開班底金 + 現金訂單 paid_amount − 現金訂單 refunded_amount。paid_amount 已扣原找零，不得再次加實收現金或售價；LINE Pay、信用卡、未付款訂單不增加錢櫃現金。
- totalRevenue 是有效品項售價合計（含尚未收款品項），與現金淨收款分開。revenue_by_payment 保存各付款方式的已收款減退款。
- 損耗成本獨立顯示，不能再從應有現金扣除。成本未建檔時 totalWastageCost=null、costComplete=false，不用售價冒充成本。
- 日結及損耗統計依總單 business_date，與明細 created_at 無關。班次、日期預設值與記錄時間共用台北 Clock。
- 關班與原有 POS 建單／取消共用 daily_shift 行鎖；原料報廢也先鎖當日班次，再鎖庫存。關班後禁止訂單異動及新增原料損耗。
- 盤點、開關班、原料報廢的實際操作人都取自登入帳號。舊 staffId 請求欄位暫留相容，但不採用。

## 訂單報廢 API

`POST /api/wastages/order-item/{itemId}`：

```json
{"reason":"製作錯誤","refundAmount":0}
```

- 僅製作中／完成待取餐的品項可以報廢。待製作請使用原取消 API；已取餐退款不在本期範圍。
- 預設 refundAmount=0，不會因報廢就假設已完成退款。只有店員已處理人工退款，才明確填入實際退款金額。
- 呼叫既有 OrderService 取消交易，統一更新總單金額、狀態及退款，已製作不回補、不再扣一次原料。重送已報廢明細不重複退款。

## 原料報廢需要一次經團隊同意的資料庫升級

過去的程式只扣庫存，原因與每次成本沒有保存，舊資料無法事後完整還原。本次增加 `raw_material_wastage` 表保存數量、成本快照、原因、回報人、時間及營業日；不覆寫 inventory 的盤點欄位。

1. 資料庫負責人先審查 [009_raw_material_wastage.sql](../database/009_raw_material_wastage.sql)。需要先在本機確認並備份，再安排共用庫升級；不要自行對 Aiven 執行。
2. 009 未升級前，預設 `pos.raw-wastage.enabled=false`。原料報廢 API 回 503，**不扣庫存**；日結與訂單報廢仍可使用。摘要的 rawWastageEnabled=false 表示尚未納入原料事件紀錄，不能解讀為歷來沒有原料損耗。
3. 確認升級成功後，在本機未追蹤的 `.pos-local.properties` 加 `pos.raw-wastage.enabled=true`，重新啟動後端。這項設定本身不會建表。
   **更新：共用庫已建立 `raw_material_wastage`，`application.properties` 已改為預設啟用。** 連到沒有這張表的資料庫時，改在 `.pos-local.properties` 設 `pos.raw-wastage.enabled=false`。
4. 啟用後扣料與損耗事件寫入共用一筆資料庫交易；事件寫入失敗則一起回滾。未知成本保存 null，不以售價估算。

沒有改動 Aiven、沒有執行 009、沒有自動重啟目前正在使用的舊後端。

## 驗證

新增 ClosingWastageRegressionTest：涵蓋現金／非現金／未付款與部分退款、操作人偽造、原料成本快照、訂單報廢重送、關班後限制、未知成本、損耗事件寫入失敗回滾、現金差額原因，以及結帳與關班鎖定。

另外補驗信用卡、全額現金退款、明細時間跨日、超額退款回滾。原料報廢與手動扣料各以兩個執行緒同時扣最後 3 份（各要 2 份），各重複 3 次，要求只有一方成功，最終庫存 1 份。

測試明確清除外部設定匯入、停用 demo 初始化並使用 H2 記憶體資料庫，不連 Aiven。實際 MySQL 升級需由資料庫負責人另行確認。

2026-11-05：修正版後端全套 72 項測試通過、bootJar 成功；前端 44 項測試仍全通過（前端沒有改動）。
帳務與損耗 21 項回歸測試再次獨立重跑，全數通過。
