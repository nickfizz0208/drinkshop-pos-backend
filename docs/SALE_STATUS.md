# 停售管理 API（022）

需要先執行 `database/022_sale_status.sql`、`database/023_material_category.sql`。

## 規則

- 飲品、加料、原料、半成品都可以停售（`on_sale = false`）。
- 原料停售 → 材料清單（`product_recipe`）用到它的飲品、指向它的加料一起不能賣。
- 半成品停售，或做它的原料（`prep_item_ingredient`）有一項停售 → 用到這個半成品的飲品、加料一起不能賣。
- 飲品、加料自己的 `on_sale` 不會被原料改寫：原料恢復販售後，因它停售的品項自動恢復；自己被停售的維持停售。
- 不能賣的飲品、加料不會出現在 `GET /api/menu`；送單時用到會回 409，例如「「珍珠奶茶」已停售（珍珠 停售）」。
- 紙杯不在材料清單裡，停售紙杯不會連動任何飲品。
- 配方查看只看飲品自己的 `on_sale`，原料停售不影響查看配方。

## `GET /api/sale-status`

登入即可。回傳四個清單：

| 欄位 | 內容 |
| --- | --- |
| `inventories[]` | `inventoryId`、`name`、`category`（分類，null＝未分類）、`onSale`、`usedByProducts`、`usedByToppings`（直接或透過半成品用到它的編號） |
| `preps[]` | `prepId`、`name`、`category`、`onSale`（自己）、`available`（含原料）、`inventoryIds`、`usedByProducts`、`usedByToppings` |
| `products[]` | `productId`、`name`、`category`、`onSale`（自己）、`sellable`（實際能不能賣）、`inventoryIds`、`prepIds`、`stoppedBy`（造成停售的材料名稱） |
| `toppings[]` | `toppingId`、`name`、`onSale`、`sellable`、`inventoryId`、`prepId`、`stoppedBy` |

## `PUT /api/sale-status`

只限門市 POS、店長、總部（其他回 403）。一次送多項變更，沒有變更的種類可以不給；任一項編號不存在回 404，整批不存。回傳更新後的完整狀態（同 GET）。

```json
{
  "inventories": [{ "id": 6, "onSale": false }],
  "preps":       [{ "id": 1, "onSale": false }],
  "products":    [{ "id": 5, "onSale": true }],
  "toppings":    []
}
```

## `PUT /api/sale-status/categories`（023）

只限店長、總部。改一項原料或半成品的分類；分類是文字（最多 20 字），空白＝未分類。回傳更新後的完整狀態。

```json
{ "kind": "INVENTORY", "id": 8, "category": "水果" }
```

`kind` 是 `INVENTORY`（原料）或 `PREP`（半成品）。飲品的分類沿用 `product.category`。
