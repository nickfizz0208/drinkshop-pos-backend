# 手搖飲 POS 後端

目前已完成第一階段：菜單／規則查詢、員工身份驗證、建立多杯訂單、後端計價與扣料、製作狀態、取消回補、已登入員工棄單與操作人紀錄及損耗照片 API。

技術：Java 21、Spring Boot 3.5.16、Gradle 8.14.3、Spring JDBC、MySQL。
Angular 前端已串接；會員地圖、菜單編輯、配方查看、盤點與正式開班日結介面待其他模組接入。

## 啟動與測試

從本專案根目錄執行：

```powershell
& .\scripts\start-pos.ps1 -Build
```

啟動類別：`com.example.drinkshop.DrinkshopApplication`。預設網址 `http://localhost:8083`，只監聽本機。
健康檢查：`GET /api/health`。

```powershell
.\gradlew.bat --gradle-user-home "$env:USERPROFILE\.gradle" test bootJar
```

## 使用與串接

- [API 路徑、Postman 請求範例與限制](docs/POS_API.md)
- [資料庫與假資料](database/README.md)
- [虛構原料成本與每杯成本範例](docs/DEMO_COSTS.md)
- [與團隊對齊的最新 ER 圖](docs/database-er.mmd)
- 金額使用 DECIMAL(10,2)／BigDecimal，明細加料採獨立主鍵，距離與時間為 distance（公尺）、duration（秒）。
- 本機帳密 在已被 Git 忽略的 `.pos-local.properties`，請勿提交或公開。
- 目前以 HTTP Basic 驗證 `staff`／`manager`，每次請求檢查帳號是否在職；不是 JWT 登入介面。
- 取消 API 記錄人工完成退款後的結果，沒有串接實際金流。
- `unit_price` 包含加料；同規格明細可有多杯，目前取消整筆明細，不拆單杯。

開發時建議使用 start-pos.ps1：先檢查 8083 是否占用，再以獨立的 JAR 副本啟動；之後重新打包不會覆寫正在執行的 JAR。重啟前先停止舊服務，避免兩份後端競用連接埠。

新增未付款接單、稍後收款、取消時不退／部分退／全退與原料損耗快照。資料欄位與升級方式見 [PAYMENT_AND_WASTAGE.md](docs/PAYMENT_AND_WASTAGE.md)。本機已套用 007，毋須重跑。

組員從這裡開始：[TEAM_QUICKSTART.md](docs/TEAM_QUICKSTART.md)。目前棄單不需店長帳號或 PIN，由有效登入員工操作並記錄本人。
