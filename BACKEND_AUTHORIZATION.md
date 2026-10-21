# 後端權限

管理員定義為資料庫中的 `admin` 帳號。每次請求驗證 JWT 的簽章與有效期限，再查詢帳號是否存在，依資料庫回傳的正式帳號授予 ADMIN 或 USER。前端角色、request body、query string 或 JWT 自訂 role claim 都不決定管理權限。

| API | 權限 |
| --- | --- |
| POST `/api/user/login`、`/register` | 公開；可在夾帶失效 Token 時重新登入 |
| GET `/api/quiz/list`、`/detail/{id}` | 公開 |
| GET `/api/quiz/statistics/options/{id}`、`/summary/{id}` | 公開，只提供選項票數、總份數及各題作答人數 |
| POST `/api/quiz/submit`、`/api/user/logout` | 登入帳號 |
| GET `/api/quiz/my-submissions` | 登入帳號，只取得該 JWT 帳號自己的紀錄，不接受帳號指定 |
| POST `/api/quiz/create`、`/update`、`/delete` | admin |
| GET `/api/quiz/statistics/submissions/{id}` | admin，含個別作答及姓名、信箱 |
| 其他路徑與未列出的 HTTP 方法 | 預設拒絕 |

缺少或無效憑證回傳 JSON 401；已登入但權限不足回傳 JSON 403。OPTIONS 預檢與 localhost:4200 的 CORS 請求維持可用。

前端的 admin 路由加入守衛；一般統計頁只使用公開彙總，僅 admin 顯示簡答內容及會員明細。個人中心改讀 `/my-submissions`。移除舊的共用 LocalStorage 明細，切換帳號時清空記憶體中的資料，延遲回傳的舊帳號請求也不會重新填入明細。

## 驗證與啟用

後端 26 項測試通過（含 13 項權限整合案例），使用 H2 記憶體資料庫，不修改現有 MySQL。前端完整 32 項測試與 production 建置通過；舊範本測試已改為目前頁面與權限顯示的設定。完整清單見 TESTING.md。

Eclipse 選取專案按 F5，停止並重新啟動後端，再重新整理瀏覽器載入新版前端。無需修改資料表或密鑰；原本有效的 Token 可繼續使用。

手動確認：admin 可管理問卷並查看明細；user02 可填寫及查看公開統計、自己的個人紀錄，但不能進入 admin 路由。以 user02 Token 直接呼叫管理 API 或全體明細 API 應回傳 403，以未登入狀態呼叫應回傳 401。
