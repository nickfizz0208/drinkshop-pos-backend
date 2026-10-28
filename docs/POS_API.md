# POS 點餐與棄單 API（第一階段）

最新收退款與成本約定見 [PAYMENT_AND_WASTAGE.md](PAYMENT_AND_WASTAGE.md)，以下早期範例以現在收款為主。

本階段只做你負責的 POS 流程。會員地圖、菜單編輯、員工管理、手機配方及正式日結介面由其他模組後續接入。
技術：Spring Boot 3.5.16、Java 21、Gradle 8.14.3、Spring JDBC、MySQL。
使用 Controller → Service → Repository 分層，SQL 對應既有表；沒有 JPA/Hibernate 自動建表，也沒有分散式元件。
新 package 為 `com.example.drinkshop`。舊問卷程式與測試保留於檔案中，但排除於 Gradle 建置，啟動時不會掃描或執行。

## 啟動

於後端根目錄執行：

```powershell
& .\scripts\start-pos.ps1 -Build
```

或 Eclipse 重新整理 Gradle 專案後執行 `DrinkshopApplication`，工作目錄須為後端根目錄。
預設只監聽 `127.0.0.1:8083`，沒有變更前端。`GET http://localhost:8083/api/health` 應回傳 `status=UP`。
不要再執行 `Quiz1150720Application`。

本機 `.pos-local.properties` 已排除於 Git，包含本機資料庫設定與本機測試密碼及 PIN。
請自行在本機查看，不要把檔案貼上 GitHub。組員請依 TEAM_QUICKSTART.md 設定自己的環境。

- 店員：`staff`
- 店長：`manager`
- 兩個帳號的測試密碼：本機檔案的 `pos.demo.password`。
- 目前取消／棄單不使用 PIN 或店長授權，記錄有效登入的操作員工。
- 原本 `demo_staff`、`demo_manager` 仍停用，不會被啟用或覆寫。

目前使用 **HTTP Basic**，每個請求帶員工帳密；Postman 選 Authorization → Basic Auth。
這是可獨立測試的帳號介面，尚未實作 JWT 登入／登出頁，後續可和帳號組員整合。
伺服器每次查資料庫檢查帳號與在職狀態，身份不接受 request body 指定。
目前只供本機開發；外部部署前須加入 HTTPS、合適的登入機制與嘗試次數限制。

本機開啟 `pos.demo.enabled=true` 時，第一次啟動只新增不存在的兩個測試帳號，使用 BCrypt 密碼雜湊。
不覆寫既有帳號、不重新啟用離職帳號。若今天沒有開班紀錄，建立底金 2000 的測試開班；已關帳的日期不重新開啟。
這個選項預設為 false，不能當成正式開班 API。

## 資料庫

既有 `drinkshop_pos` 已套用 `004_pos_api.sql` 及 `005_team_er_alignment.sql`，不需再執行。
最新 ER 圖在 [database-er.mmd](database-er.mmd)，可直接貼到 Mermaid Live Editor。
ID 保留 BIGINT，避免縮小既有主鍵；金額與關聯依團隊 ER 圖調整。
它保留 10 張表，新增兩欄：

- `pos_order.request_hash`：檢查同 requestId 是否帶不同內容。
- `order_item.deducted_inventory_json`：記錄此筆明細實際總扣料量，取消時精確回補。

005 將各金額改為 `DECIMAL(10,2)`；明細加料使用 `order_topping_id` 自增主鍵，另保留 `(item_id,topping_id)` 唯一限制。
距離改名為 `distance`（公尺，最多兩位小數），時間為 `duration`（整數秒）。
新環境依序執行 `001_schema.sql`、`002_demo_data.sql`、`004_pos_api.sql`、`005_team_er_alignment.sql`、`006_demo_ingredient_costs.sql`，再依新文件套用 007。各支只執行一次，已存在資料庫不可重跑 001。
程式不自動執行建表或 migration，也不讀寫問卷資料庫。
資料庫帳號密碼可用 `POS_DB_USERNAME`、`POS_DB_PASSWORD`；URL 可用 `POS_DB_URL`。本機忽略檔的直接設定會覆蓋這些預設值，臨時覆蓋可用 `SPRING_DATASOURCE_*`。

## API 清單

除 health 外都需要有效員工的 Basic Auth。
請求欄位採 camelCase，既有資料列回應採 snake_case；金額為新台幣元、最多兩位小數，Java 使用 BigDecimal 精確計算；距離公尺、時間秒。
金額上限為 99999999.99，超過範圍或精度會拒絕，不自動四捨五入。
成功直接回傳物件或陣列（HTTP 200）；失敗為 `{"success":false,"message":"中文原因"}`。

| 方法與路徑 | 用途 |
| --- | --- |
| GET `/api/health` | 應用與資料庫連線檢查 |
| GET `/api/auth/me` | 取得目前員工 ID、姓名、等級；不回傳密碼／PIN |
| GET `/api/menu` | 已上架飲料及加料，不回傳配方 |
| GET `/api/rules` | 前端需要的規則、條件、動作、提示 |
| GET `/api/orders?date=2026-10-28` | 營業日訂單列表；未帶日期預設台灣今天 |
| GET `/api/orders/{id}` | 訂單、明細與加料 |
| POST `/api/orders/{id}/collect` | 未付款單稍後收款 |
| POST `/api/orders` | 結帳、後端計價、儲存訂單與扣料 |
| POST `/api/orders/{id}/items/{itemId}/status` | 推進製作狀態 |
| POST `/api/orders/{id}/items/{itemId}/cancel` | 取消整筆明細（包含該筆全部杯數） |
| POST `/api/orders/{id}/cancel` | 取消整張訂單所有尚未取消的明細 |
| POST `/api/orders/{id}/items/{itemId}/photo` | multipart 的 `file` 欄位，上傳一張損耗照片 |
| GET `/api/orders/{id}/items/{itemId}/photo` | 登入後取得 PNG 照片 |

菜單只查詢上架商品，尚未提供菜單編輯或獨立售完欄位。
目前沒有配送運費計價介面，結帳的運費為 0；距離、車程及當次地址備註可傳入，但不保留歷史完整地址。

### 建立訂單

同一張訂單包含多筆明細，每筆相同規格可有多杯。以下兩杯大紅茶加珍珠，每杯 40，再加一杯中奶茶加茶凍 50，總共 130。

```json
{
  "requestId": "bdb1ec14-bb1a-42c0-8e6e-56ab65dcb491",
  "source": "TAKE_OUT",
  "payment": "CASH",
  "receivedCash": 200,
  "note": "測試訂單",
  "items": [
    {"productId": 1, "size": "L", "temperature": "COLD", "sugar": "HALF", "ice": "LESS", "qty": 2, "toppingIds": [1], "note": "兩杯相同"},
    {"productId": 3, "size": "M", "temperature": "COLD", "sugar": "NORMAL", "ice": "NORMAL", "qty": 1, "toppingIds": [2]}
  ]
}
```

前端每次「新結帳」產生一個 UUID `requestId`，同一次請求逾時重試必須使用原 UUID 及相同內容。
同一員工、同 requestId、同內容只建一張訂單；換內容或員工重用 UUID 回 409。
不要傳 `total`、`unitPrice`、`staffId`；後端不接受未定義欄位，避免誤以為可以指定價格或身份。
`unit_price` 已含加料，不可再將 `price_at_order` 加到小計。

可選值：

- source：`DINE_IN / TAKE_OUT / PHONE_DELIVERY / UBER_EATS`
- payment：`CASH / CREDIT_CARD / LINE_PAY`；Uber Eats 僅作為訂單來源，新單不可選為付款方式。歷史資料的付款標記仍保留。
- size：`M / L`
- temperature：`COLD / WARM / HOT`
- sugar：`NORMAL / LESS / HALF / LIGHT / NONE`
- ice：`NORMAL / LESS / LIGHT / NONE`
- qty：1～100；每張最多 100 筆明細；額外加料目前每種一次，不支援半份／雙倍。

選填欄位：`memberPhone`、`distance`、`duration`、`addressNote`、`note`。
舊請求名稱 `distanceM`、`durationSeconds` 暫時作為輸入別名保留；回應一律為 `distance`、`duration`。
`receivedCash` 可傳 `200.25`；JSON 金額維持 number，前端顯示時可格式化為兩位小數。
非現金付款不要帶 `receivedCash`。既有會員電話必須存在；會員建立與地圖查詢不在本階段。
付款方式僅記錄人工收款結果，**沒有實際串接信用卡、LINE Pay 或 UberEats 收款／退款**。

### 狀態與棄單

狀態 API 只接受相鄰推進：`PENDING → MAKING → DONE → PICKED_UP`。

整單操作：POST `/api/orders/{id}/status`，同樣傳 `{"status":"MAKING"}`、`DONE` 或 `PICKED_UP`。
MAKING 只推進待製作品項；DONE 只推進製作中品項。取消、損耗、已完成或已取餐品項不倒退。
整單取餐須所有保留品項皆 DONE／PICKED_UP 且已收款；後端一次交易更新，不會部分成功。逐項狀態 API 繼續保留供分批處理。

```json
{"status":"MAKING"}
```

取消 API：

```json
{"reason":"客人取消","refundAmount":0}
```

若明細是 `PENDING`，取消後變 `CANCELLED` 並依實際扣料快照回補。
`MAKING` 或 `DONE` 由有效登入員工直接轉損耗，不需 PIN：

```json
{"reason":"已製作但客人棄單","refundAmount":0}
```

通過後變 `WASTED`、以 approved_by 記錄實際登入的操作員工，不回補庫存。`PICKED_UP` 不提供本階段退款操作。
同一明細重複取消不重複回補；整單取消中任何一筆不合法，整張操作回滾。
同規格兩杯若只取消其中一杯，尚未提供拆分功能；目前取消的是該明細全部杯數。
已日結的營業日不能新增或修改訂單；跨日退款／補退不在本版。

取消 API 可明確指定不退款（refundAmount=0）或部分／全額退款；正數代表已由店員完成人工退款的記錄。未付款退款只能為零。
取消後 `total` 為保留品項金額；淨收款應用 paid_amount − refunded_amount，不再用 total。原品項價格保存在明細。
現金單的 `received_cash` 保留原實收，`change_cash` 是累計退還現金（原找零＋退款），不要誤認為只含最初找零。
這個簡化版沒有退款交易流水，不能當完整金流帳務。

### 照片

先取消轉成 WASTED，再以 multipart `file` 上傳 JPEG／PNG。
限制原檔 5 MB、1200 萬像素、每筆一張；不支援 HEIC、PDF、SVG 或任意檔案。
後端檢查實際圖片並轉成 PNG，用隨機名稱存於 `uploads/wastage`，不接受使用者提供路徑。
透過有登入驗證的 photo API 讀取，不公開整個 uploads 目錄。
前端已有拍照選圖／預覽／上傳畫面，相機仍需實機測試。

## 交易與驗證

- 同一營業日鎖定 `daily_shift` 再操作，先採簡單序列化結帳；相關日結／盤點組員未來也必須遵守共同鎖定順序。
- 庫存依 ID 排序鎖定後檢查，**本階段庫存不足回 409、阻擋結帳**；這是目前避免負庫存的實作選擇，團隊若採只提醒需一致調整資料庫限制。
- 訂單、明細、加料及扣料在同一交易，任何失敗全部回滾。
- 前端依規則反灰／退選，後端遇到違規內容回 400，不在結帳時偷偷改客人的選項。
- 不支援的條件／規則不會靜默跳過，回 409 請檢查規則資料。JSON 規則契約參照目前 seed。

測試指令：

```powershell
.\gradlew.bat --gradle-user-home "$env:USERPROFILE\.gradle" test bootJar
```

整合測試使用 H2 記憶體資料庫，不修改本機 MySQL。
`scripts/verify_mysql_api.py` 額外以隨機名稱建立獨立 MySQL 測試庫，啟動實際 HTTP 服務驗證後清除該測試庫；不操作現有 POS 訂單。
H2 DDL 是基礎 schema 加 004／005 的測試用鏡像，未來修改 MySQL DDL 也要同步測試檔。
目前 36 項 H2 整合測試通過；實際 MySQL／HTTP 檢查亦涵蓋小數計價、現金找零與退款、獨立加料主鍵和距離／時間改名。
`100`、`100.0`、`100.00` 的現金值會正規化為相同請求身份；保留舊欄位雜湊格式，避免原有重送識別失效。

Boot 3 與 Gradle 8 的組合依官方需求選定：[Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。

## 既有資料庫升級至團隊 ER 圖

本機已完成，不用再跑。其他同樣使用 001＋004 的環境，先停止 API，再執行 `scripts/align_team_er.py`。
腳本先檢查舊 schema 與金額範圍，以 mysqldump 備份至已被 Git 忽略的 `build/db-backups/`，再套用 005。
會比對所有表的既有資料（只忽略新增代理主鍵，數值依精確值比較），並確認舊問卷資料不變。
MySQL DDL 不支援整批回滾；若遇到非預期的部分升級狀態，腳本會停止，不自行刪除資料或重跑。
資料庫備份包含帳號雜湊，不要提交或公開。

啟動腳本會把 JAR 複製至 build/runtime 的獨立目錄再執行，避免重建 bootJar 破壞舊 JVM 的類別載入。已在執行時會明確拒絕另啟服務，請先停止舊服務再重啟。

已套用 006／007；新單保存成本快照，訂單詳情回傳收款、退款與成本欄位，見 PAYMENT_AND_WASTAGE.md。

目前團隊流程不使用 PIN：舊 Cancel 請求的 managerUsername／managerPin 僅為相容舊客戶端保留，不參與授權或指定操作身份，前端不再傳送。approved_by 是已製作棄單的登入操作人，不能解讀為店長核准。
