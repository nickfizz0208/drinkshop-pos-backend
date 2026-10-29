# 組員查看與啟動

目前完成 POS 點餐、含加料多杯計價、收款／稍後收款、逐項及整批製作取餐、取消退款、原料損耗與照片。訂單分成待處理、待取餐、當日紀錄。

**目前團隊決定：棄單不使用 PIN 或店長授權。有效登入員工可操作，已製作棄單的 approved_by 記錄實際登入員工，不表示店長已核准。** 本次不刪除舊 pin_hash 或歷史授權欄位，避免破壞既有資料。

- 後端：https://github.com/nickfizz0208/drinkshop-pos-backend
- 前端：https://github.com/nickfizz0208/drinkshop-pos-frontend

GitHub 是原始碼與說明，沒有自動部署可操作網站，也不包含開發者電腦上的 MySQL 資料。要快速看成果可先由開發者分享螢幕；自行操作需完成以下環境。

## 後端（Java 21、Windows）

資料庫已架在 Aiven 雲端（`drinkshop_pos`），全組共用，**不需要安裝 MySQL，也不要再執行 database/ 裡的 SQL**。

1. 用 SourceTree Clone 後端，工作目錄切到專案根目錄；不用安裝全域 Gradle。
2. 複製 `.pos-local.properties.example` 為 `.pos-local.properties`，把組內共用文件上的內容整段貼上。此檔被 Git 忽略，不可提交。
3. 執行 `.\scripts\start-pos.ps1 -Build`；或用 IDE 啟動 `com.example.drinkshop.DrinkshopApplication`。若 IDE 提示升級 Gradle wrapper，請拒絕，維持 8.14.3。
4. 確認 http://127.0.0.1:8083/api/health 回傳 UP。

`pos.demo.enabled=true` 時，後端啟動會建立不存在的 staff、manager 測試帳號，並開今天的班；不覆寫既有帳號或重新開啟已關帳日期。每天第一次使用前需有人啟動一次後端。

雲端資料庫要重建時（例如被誤刪），由一位組員依 [database/README.md](../database/README.md) 用 Workbench 執行，其他人不要重複執行。

## 前端

1. Clone 前端，於該專案根目錄執行 `npm ci`。
2. 執行 `npm start`，開啟 http://localhost:4200。
3. 登入 staff，密碼見共用文件（`pos.demo.password`）。

前端 /api 代理到 127.0.0.1:8083，因此後端也必須執行。初始預覽畫面不會建立訂單。使用 Angular 19 相容的 Node 環境；作者本機 Node 24 已能建置，但團隊版本仍待統一。

## 可示範流程

選兩杯飲料與加料 → 現在收款或稍後收款 → 待處理 → 逐項或全部開始／完成製作 → 待取餐 → 收款後整單取餐 → 當日紀錄。

取消另測：未製作回補庫存；已製作轉損耗、不回補；已收款可不退、部分退或全退；未收款退款為零。收退款為人工處理後記錄，沒有串金流。成本是假資料估算，不是淨損益；歷史未存成本顯示未建檔。

會員地圖、正式員工管理／配方查看、盤點與日結介面尚待模組整合。詳細契約見 POS_API.md、PAYMENT_AND_WASTAGE.md、database-er.mmd。後端 36 項整合測試與前端測試可在各專案執行；本機密碼、上傳照片、資料庫備份與建置輸出均不提交。
