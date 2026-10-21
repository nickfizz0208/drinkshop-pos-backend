# PulseForm 問卷系統：Spring Boot 後端

課堂專題用問卷系統，搭配 Angular 前端。支援帳號登入、admin 問卷管理、單選／多選／簡答、條件跳題、同帳號重填覆蓋、公開統計與個人作答紀錄。

## 專案位置與需求

- 後端：`C:\java_project1\quiz_1150720`，Java 21、Spring Boot、Spring Security、JPA、MySQL 8。
- 前端：`C:\Users\User\Desktop\Angular\demo6`，Angular 19。
- 後端網址：`http://localhost:8083`；前端網址：`http://localhost:4200`。
- 根目錄另有舊的 `quiz_1150720/` 子資料夾。本次維護、啟動與測試都使用根目錄的 `src/` 與 `build.gradle`。

## 第一次啟動

在 MySQL 建立資料庫（不清除既有資料）：

```sql
CREATE DATABASE IF NOT EXISTS quiz_1150720 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

依本機環境修改 `src/main/resources/application.properties` 的 datasource 連線設定，或設定 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。目前 `ddl-auto=update` 會補齊缺少的表格／欄位；這不是正式環境的版本化 migration。

JWT 密鑰讀取根目錄 `.jwt.properties` 的 `jwt.local-secret`，或優先使用環境變數 `JWT_SECRET`。這部電腦已建立本機密鑰，請保留，不需要重新產生。

新電腦缺少密鑰時，在後端根目錄執行以下 PowerShell；已有檔案時不會覆蓋：

```powershell
if (-not (Test-Path .jwt.properties)) {
    $jwtBytes = New-Object byte[] 32
    $jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $jwtRandom.GetBytes($jwtBytes)
    $jwtRandom.Dispose()
    ('jwt.local-secret=' + [Convert]::ToBase64String($jwtBytes)) | Set-Content -Encoding ascii .jwt.properties
}
```

此檔案已排除於 Git。更換密鑰會讓舊 Token 失效；密鑰相同且 Token 未過期時，後端重啟後仍可使用。

## 啟動、測試與打包

```powershell
cd C:\java_project1\quiz_1150720
.\gradlew.bat --gradle-user-home "$env:USERPROFILE\.gradle" bootRun
```

Eclipse 可直接執行 `Quiz1150720Application`；外部檔案修改後先選取專案按 F5。啟動工作目錄應為後端根目錄，並確認 8083 沒有另一份後端占用。

```powershell
.\gradlew.bat --gradle-user-home "$env:USERPROFILE\.gradle" test bootJar
```

JUnit 整合測試使用 H2 記憶體資料庫，不會清除或修改本機 MySQL。測試報告在 `build/reports/tests/test/index.html`，可執行 JAR 在 `build/libs/`。

## 開發帳號與權限

新資料庫首次啟動時，DataInitializer 會建立開發帳號；已有同名帳號時不會重設密碼。

| 帳號 | 新建帳號的開發密碼 | 權限 |
| --- | --- | --- |
| admin | 123456 | 管理問卷、查看所有作答明細 |
| user02 | 123123 | 填寫、查看自己的紀錄及公開統計 |

一般帳號直接呼叫管理 API 回傳 403；缺少或失效 JWT 回傳 401。姓名、信箱、簡答與個別答案只向 admin 提供，自己的作答另有登入 API。詳細路徑見 [BACKEND_AUTHORIZATION.md](BACKEND_AUTHORIZATION.md)。

## 作答規則

- 以 Asia/Taipei 日期判斷，僅已發布且 `開始日 <= 今天 <= 截止日` 的問卷可送出。
- 必填簡答的空白不算答案；必填選擇題須選有效選項。題目必須屬於該問卷，選項必須屬於該題。
- 單選與簡答不得重複提供；多選不得重複選取相同選項。全部題目都非必填時，可送出空答案清單。
- 條件跳題依來源選擇題的選項決定是否顯示，隱藏題目不要求必填，也不保存舊的隱藏答案。
- 同帳號重填會在同一交易中先刪明細、再刪主檔，寫入最新內容；同帳號並行送出有資料列鎖。驗證或寫入失敗時，舊答案保留。
- API 的 `submitTime` 是資料庫的實際送出時間，讀取或重新整理不會改變時間。

條件跳題新增 `question.parent_question_no`、`question.trigger_option_no` 兩個可空欄位，重啟時由 Hibernate 補齊。早期僅存在瀏覽器的跳題設定，請由 admin 在原瀏覽器開啟該問卷編輯並重新儲存一次，才會同步到後端；有條件必填的舊問卷需先完成此步驟。

## 展示與後續

依 [TESTING.md](TESTING.md) 進行展示與驗收。這是本機課堂版本；正式部署仍需正式帳密、HTTPS、受控的秘密設定、資料庫 migration 與備份。前端圖片與其他選項標記等 UI 設定仍有瀏覽器本機保存的部分，跨電腦不保證同步。
