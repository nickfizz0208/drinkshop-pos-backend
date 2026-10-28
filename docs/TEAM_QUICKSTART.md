# 組員查看與啟動

目前完成 POS 點餐、含加料多杯計價、收款／稍後收款、逐項及整批製作取餐、取消退款、原料損耗與照片。訂單分成待處理、待取餐、當日紀錄。

**目前團隊決定：棄單不使用 PIN 或店長授權。有效登入員工可操作，已製作棄單的 approved_by 記錄實際登入員工，不表示店長已核准。** 本次不刪除舊 pin_hash 或歷史授權欄位，避免破壞既有資料。

- 後端：https://github.com/nickfizz0208/drinkshop-pos-backend
- 前端：https://github.com/nickfizz0208/drinkshop-pos-frontend

GitHub 是原始碼與說明，沒有自動部署可操作網站，也不包含開發者電腦上的 MySQL 資料。要快速看成果可先由開發者分享螢幕；自行操作需完成以下環境。

## 後端（Java 21、MySQL 8、Windows）

1. Clone 後端，工作目錄切到專案根目錄；不用安裝全域 Gradle。
2. 複製 `.pos-local.properties.example` 為 `.pos-local.properties`，填自己的 MySQL 帳密。此檔被 Git 忽略。
3. 在 MySQL Workbench 依序執行 database/001_schema.sql、002_demo_data.sql、004_pos_api.sql、005_team_er_alignment.sql、006_demo_ingredient_costs.sql，各執行一次。既有 POS 資料庫請先核對升級進度，不可重跑種子資料。不要連舊 quiz 資料庫。
4. 尚未套用 007 時，先停止後端，安裝 Python 3（只需標準函式庫），於後端根目錄執行 `python scripts/migrate_payment.py`。它使用標準 MySQL 8 安裝位置的 mysql.exe／mysqldump.exe，先備份再套用 007；若安裝位置不同，調整腳本 BIN 常數。不要直接執行缺少移除舊約束步驟的 007。
5. 執行 `./gradlew.bat test bootJar`，再執行 `./scripts/start-pos.ps1`；或使用 Eclipse Gradle 匯入後啟動 `com.example.drinkshop.DrinkshopApplication`。不要啟動舊 Quiz1150720Application。
6. 確認 http://127.0.0.1:8083/api/health 回傳 UP。

範例設定啟用時建立不存在的 staff、manager 測試帳號（密碼 123456）及當日開班；不覆寫既有帳號或重新開啟已關帳日期。這些是本機展示帳號。

## 前端

1. Clone 前端，於該專案根目錄執行 `npm ci`。
2. 執行 `npm start`，開啟 http://localhost:4200。
3. 登入 staff／123456；若組員改了 demo 密碼，以自己的設定為準。

前端 /api 代理到 127.0.0.1:8083，因此後端也必須執行。初始預覽畫面不會建立訂單。使用 Angular 19 相容的 Node 環境；作者本機 Node 24 已能建置，但團隊版本仍待統一。

## 可示範流程

選兩杯飲料與加料 → 現在收款或稍後收款 → 待處理 → 逐項或全部開始／完成製作 → 待取餐 → 收款後整單取餐 → 當日紀錄。

取消另測：未製作回補庫存；已製作轉損耗、不回補；已收款可不退、部分退或全退；未收款退款為零。收退款為人工處理後記錄，沒有串金流。成本是假資料估算，不是淨損益；歷史未存成本顯示未建檔。

會員地圖、正式員工管理／配方查看、盤點與日結介面尚待模組整合。詳細契約見 POS_API.md、PAYMENT_AND_WASTAGE.md、database-er.mmd。後端 36 項整合測試與前端測試可在各專案執行；本機密碼、上傳照片、資料庫備份與建置輸出均不提交。
