# Commit 訊息規範

每筆 commit 用 `<類型>: <一句話說明>`。中文、英文都可以，標題寫清楚這次改了什麼。若需說明原因、相容性或驗證方式，空一行再寫內文。

| 類型 | 用途 | 範例 |
| --- | --- | --- |
| `feat:` | 新增功能或能力 | `feat: add Cloudflare container deployment for POS backend` |
| `fix:` | 修正錯誤 | `fix: 修正日結現金差額計算` |
| `refactor:` | 調整程式結構，維持對外行為 | `refactor: 抽出班表日期轉換邏輯` |
| `doc:` | 新增或更新文件 | `doc: 補充本機開發設定` |
| `test:` | 新增或更新測試 | `test: 驗證 JWT 登入回傳內容` |
| `chore:` | 維護依賴、工具或建置設定 | `chore: 更新 Gradle wrapper` |

- 一筆 commit 聚焦一個目的。功能、修正與無關的整理可以拆開。
- 提交前用 `git diff --cached --name-only` 確認檔案範圍。`.pos-local.properties`、密碼和金鑰留在 Git 之外。
