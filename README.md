# 手搖飲 POS 後端

目前已完成第一階段：菜單／規則查詢、員工身份驗證、建立多杯訂單、後端計價與扣料、製作狀態、取消回補、已登入員工棄單與操作人紀錄及損耗照片 API。

技術：Java 21、Spring Boot 3.5.16、Gradle 8.14.3、Spring JDBC、MySQL（團隊共用資料庫架在 Aiven）。
Angular 前端已串接；會員資料 API 已完成（見 docs/MEMBER_API.md）；會員地圖、菜單編輯、配方查看、盤點與正式開班日結介面待其他模組接入。

## 連線設定（第一次 clone 必做）

資料庫帳密不放在 Git，實際內容放在組內共用文件。

1. 在專案根目錄（與 `build.gradle` 同層）把 `.pos-local.properties.example` 複製一份，改名為 `.pos-local.properties`。
2. 打開共用文件，把 `.pos-local.properties` 的內容貼進去，以共用文件上的值為準。
3. 連 Aiven 時，`POS_DB_URL` 必須帶 `sslMode=REQUIRED`，不可使用 `useSSL=false`。格式如下：

```properties
POS_DB_URL=jdbc:mysql://<host>.aivencloud.com:<port>/drinkshop_pos?sslMode=REQUIRED&serverTimezone=Asia/Taipei&characterEncoding=UTF-8
POS_DB_USERNAME=avnadmin
POS_DB_PASSWORD=<見共用文件>
pos.demo.enabled=true
pos.demo.password=<見共用文件>
```

注意：

- `.pos-local.properties` 已被 Git 忽略，請勿提交、截圖或貼到公開頻道。
- 提交前在 SourceTree 確認 Staged 清單沒有這個檔案。萬一已提交，請立刻通知組員並到 Aiven 重設密碼。
- 有人重設 Aiven 密碼時，請同步更新共用文件。
- 共用文件請設為僅限組員檢視。
- 資料庫名稱是 `drinkshop_pos`，不是 Aiven 預設的 `defaultdb`。
- `pos.demo.enabled=true` 會在後端啟動時建立 `staff`／`manager` 測試帳號，並開今天的班；少了它無法登入，也無法建立訂單。全組請用同一個 demo 密碼（第一個啟動的人建立帳號後，之後不會被覆寫）。
- 每天第一次使用前，需有一位組員啟動後端，當天才會開班。
- 雲端資料庫已建好，**組員不需要再執行 `database/` 裡的 SQL**；重建方式見 [database/README.md](database/README.md)。
- 未建立 `.pos-local.properties` 時，程式會改連本機 `localhost:3306/drinkshop_pos`。

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

開發時建議使用 start-pos.ps1：先檢查 8083 是否占用，再以獨立的 JAR 副本啟動；之後重新打包不會覆寫正在執行的 JAR。重啟前先停止舊服務，避免兩份後端競用連接埠。

## 使用與串接

- [API 路徑、Postman 請求範例與限制](docs/POS_API.md)
- [會員 API](docs/MEMBER_API.md)
- [資料庫與假資料](database/README.md)
- [虛構原料成本與每杯成本範例](docs/DEMO_COSTS.md)
- [與團隊對齊的最新 ER 圖](docs/database-er.mmd)
- 金額使用 DECIMAL(10,2)／BigDecimal，明細加料採獨立主鍵，距離與時間為 distance（公尺）、duration（秒）。
- 目前以 HTTP Basic 驗證 `staff`／`manager`，每次請求檢查帳號是否在職；不是 JWT 登入介面。
- 取消 API 記錄人工完成退款後的結果，沒有串接實際金流。
- `unit_price` 包含加料；同規格明細可有多杯，目前取消整筆明細，不拆單杯。

新增未付款接單、稍後收款、取消時不退／部分退／全退與原料損耗快照。資料欄位與升級方式見 [PAYMENT_AND_WASTAGE.md](docs/PAYMENT_AND_WASTAGE.md)。依 database/README.md 建置的資料庫已包含 007，毋須另外升級。

## 組員入門

組員從這裡開始：[TEAM_QUICKSTART.md](docs/TEAM_QUICKSTART.md)。目前棄單不需店長帳號或 PIN，由有效登入員工操作並記錄本人。