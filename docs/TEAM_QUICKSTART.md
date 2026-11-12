# 組員查看與啟動

目前完成 POS 點餐、含加料多杯計價、收款／稍後收款、逐項及整批製作取餐、取消退款、原料損耗與照片。訂單分成待處理、待取餐、當日紀錄。

飲品棄單由門市 POS 帳號操作。已製作棄單的 `approved_by` 記錄執行操作的登入帳號。

- 後端：https://github.com/nickfizz0208/drinkshop-pos-backend
- 前端：https://github.com/nickfizz0208/drinkshop-pos-frontend

GitHub 是原始碼與說明，沒有自動部署可操作網站，也不包含開發者電腦上的 MySQL 資料。要快速看成果可先由開發者分享螢幕；自行操作需完成以下環境。

## 後端（Java 21、Windows）

使用共用 Aiven 資料庫（`drinkshop_pos`）前，先向負責人確認已完成 022。升級順序見 [資料庫說明](../database/README.md)。

1. 用 SourceTree Clone 後端，工作目錄切到專案根目錄；不用安裝全域 Gradle。
2. 複製 `.pos-local.properties.example` 為 `.pos-local.properties`，把組內共用文件上的內容整段貼上。此檔被 Git 忽略，不可提交。
3. 執行 `.\scripts\start-pos.ps1 -Build`；或用 IDE 啟動 `com.example.drinkshop.DrinkshopApplication`。若 IDE 提示升級 Gradle wrapper，請拒絕，維持 8.14.3。
4. 確認 http://127.0.0.1:8083/api/health 回傳 UP。

`pos.demo.enabled=true` 時，後端啟動會建立缺少的 `staff`、`manager`、`pos` 測試帳號。營業日開班由 POS 帳號在畫面操作。

雲端資料庫要重建時（例如被誤刪），由一位組員依 [database/README.md](../database/README.md) 用 Workbench 執行，其他人不要重複執行。

## 前端

1. Clone 前端，於該專案根目錄執行 `npm ci`。
2. 執行 `npm start`，開啟 http://localhost:4200。
3. 櫃台用 `pos` 帳號登入，開班後即可點餐及上班打卡。測試環境使用各自的 demo 設定。

打卡時，到門市 POS 的「工時管理」選自己的名字，再按「打卡上班」或「打卡下班」。員工個人帳號可查看自己的紀錄。

前端 /api 代理到 127.0.0.1:8083，因此後端也必須執行。初始預覽畫面不會建立訂單。使用 Angular 19 相容的 Node 環境；作者本機 Node 24 已能建置，但團隊版本仍待統一。

## 可示範流程

選兩杯飲料與加料 → 現在收款或稍後收款 → 待處理 → 逐項或全部開始／完成製作 → 待取餐 → 收款後整單取餐 → 當日紀錄。

已製作的取消品項會轉為飲品報廢，日結記錄杯數。帳面庫存以盤點為準。已收款可不退、部分退或全退，未收款退款為零。收退款由人員處理後記錄。

個人帳號可使用排班、檢查表和盤點。櫃台與個人帳號均可查看配方，店長及總部可以編輯。櫃台製作時可從訂單品項按「看配方」。開關班和日結使用門市 POS 帳號。
