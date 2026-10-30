# 會員 API

所有路徑都需要 HTTP Basic 員工登入（同 POS API）。錯誤格式：`{"success":false,"message":"..."}`。
會員以**電話**為主鍵，格式與點餐的 `memberPhone` 相同（數字、`+`、`()`、空白、`-`，5–20 字）。建議統一只輸入數字，例如 `0912345678`，避免同一人被建成兩筆。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/members?keyword=` | 搜尋。電話或姓名包含關鍵字；不帶 keyword 時回傳最新 50 位。最多 50 筆。 |
| GET | `/api/members/{phone}` | 查一位會員（點餐時用電話帶出姓名、地址）。 |
| POST | `/api/members` | 新增會員。 |
| PUT | `/api/members/{phone}` | 修改姓名、地址、經緯度、備註。電話不能改。 |
| DELETE | `/api/members/{phone}` | 刪除會員。限店長／總部，且該會員沒有任何訂單。 |
| GET | `/api/members/{phone}/orders` | 該會員最近 50 筆訂單。 |

## 新增

```json
POST /api/members
{
  "phone": "0912345678",
  "name": "王小明",
  "address": "台北市信義路一段1號",
  "lat": 25.0330,
  "lng": 121.5654,
  "note": "少冰"
}
```

必填：`phone`、`name`（50 字內）。選填：`address`、`note`（255 字內），`lat`／`lng`（要同時填或同時不填，緯度 -90～90、經度 -180～180，最多 7 位小數）。空白字串會存成 null。

回應：

```json
{
  "phone": "0912345678", "name": "王小明", "address": "台北市信義路一段1號",
  "lat": 25.0330000, "lng": 121.5654000, "note": "少冰",
  "createdAt": "2026-10-31T00:30:00", "updatedAt": "2026-10-31T00:30:00"
}
```

## 修改

`PUT /api/members/{phone}`，欄位同新增但不含 `phone`。這是**整筆覆蓋**：沒帶的選填欄位會變成 null。前端把整筆會員資料送回來也可以，`phone`、`createdAt`、`updatedAt` 會被忽略。

## 狀態碼

| 狀態 | 情況 |
|---|---|
| 400 | 必填未填、格式錯誤、經緯度只填一個、帶了不認得的欄位 |
| 401 | 未登入或帳號停用 |
| 403 | 非店長／總部刪除會員 |
| 404 | 找不到會員 |
| 409 | 電話已是會員；刪除時該會員已有訂單 |

建立訂單時若 `memberPhone` 不存在，回傳 404「找不到此會員電話，請先建立會員」。

## 限制

- 實作使用 JPA（`entity/Member`、`repository/MemberRepository`），`ddl-auto=none`，不會自動改表。
- 地圖、距離計算尚未實作；`lat`／`lng` 只是儲存。
- 刪除有訂單的會員會被拒絕，以保留訂單紀錄。
