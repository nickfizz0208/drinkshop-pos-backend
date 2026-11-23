# POS 資料庫與測試資料

依 `drinkshop_pos_spec2.pdf` 的 10 張核心表準備，資料庫為 `drinkshop_pos`，MySQL 8.0.16 以上。
目前 POS API 已接上 drinkshop_pos；使用明確 SQL，不使用 Hibernate 自動改表。前端已提供點餐與訂單介面，新單已記錄原料成本快照。
後端使用方式參閱 `docs/POS_API.md`。

## 雲端共用資料庫（Aiven）

全組共用的資料庫架在 Aiven，資料庫名稱 `drinkshop_pos`，已依下列順序建好。**平常不需要執行這些 SQL**；只有雲端資料庫需要重建，或要在自己電腦建練習用資料庫時才執行，而且由一位組員執行一次即可。

用 Workbench 連 Aiven：Hostname／Port／Username 見共用文件，SSL 分頁選 Require，Default Schema 留空。出現 8.4 版本相容警告時按 Continue Anyway。

Workbench 預設開啟 Safe Updates，會擋下 006 的 UPDATE（Error 1175）。執行 006 前先在同一分頁執行 `SET SQL_SAFE_UPDATES = 0;`，或到 Preferences → SQL Editor 取消 Safe Updates 後重新連線。

## 執行順序

在 MySQL Workbench 中依序開啟並執行：

1. `001_schema.sql`：建立資料庫及 10 張表。
2. `002_demo_data.sql`：以交易加入測試資料與訂單造成的庫存變動。
3. `004_pos_api.sql`：新增重送請求校驗與扣料快照兩欄（既有本機已套用）。
4. `005_team_er_alignment.sql`：團隊 ER 圖調整（金額小數、加料獨立主鍵、距離時間改名；既有本機已套用）。
5. `006_demo_ingredient_costs.sql`：新增每單位成本與八種原料假成本（本機已套用）。
6. `007_payment_and_wastage_workbench.sql`：收款、退款與損耗欄位。僅供新庫或經團隊同意的舊庫升級，先備份並停止所有後端，需要 CREATE/ALTER ROUTINE 權限。完整升級後重跑會跳過，部分升級會在變更前停止；不要忽略錯誤繼續。不要直接執行 `007_payment_and_wastage.sql`。共用 Aiven 已建好，不執行這些 SQL。
7. `008_member_geo_source.sql`：會員座標來源欄位，僅供本機或團隊同意後升級；不要自行對 Aiven 執行。
8. `003_verify.sql`（選用，需在 021 以前執行）：檢查原始範例訂單金額、庫存及配方原料參照。
9. `009_raw_material_wastage.sql`：原料報廢紀錄。
10. `010-012_checklist_shift_schedule_ALL.sql`：檢查表、打卡與排班。也可以依序執行 010、011、012 三個個別檔案。
11. 依檔名順序執行 013–020，涵蓋排班發佈、POS 職級、排班標籤及盤點。
12. 執行 [`026_recipe_upgrade_workbench.sql`](026_recipe_upgrade_workbench.sql)。這支合併檔取代分開執行的 021 和 022_recipe_portions。既有庫先確認 020 以前的版本，再執行合併檔。
13. 有載入 002 示範菜單的資料庫可執行 [`027_demo_recipe_portions.sql`](027_demo_recipe_portions.sql)，補齊五款示範飲品的八筆空白材料份數。
14. 依序執行 028_sale_status、029_material_category、030_product_size_prices、031_menu_tea_milk。028_sale_status、029、030 含 DDL，部分套用後應先核對欄位、價格與訂單容量，再接續未完成的步驟。
15. 依序執行 032–046（飲品色系、茶類色系、其他商品、特調芝芝、咖色、菜單加料、溫度冰量甜度、加料份數、加料上限、客製選項、限冷飲加料、半成品用半成品做、材料停售連動、停售管理頁顯示、組合飲品），說明見下方各節。這版後端需要 032、038、039、040、041、043、044、045、046。

16. 依序執行 047–049，新增打卡補登來源、飲品售完標記與跨日排班支援。排班補登由本機設定 `pos.clock-out.apply-from` 指定首筆適用的上班打卡時間（台北時間，例如 `2026-11-24T09:00:00`）。先設定啟用時刻，再啟動服務，才會處理適用的打卡。

**舊檔名對照**：原 `022_sale_status.sql` → `028_sale_status.sql`、`023_material_category.sql` → `029_material_category.sql`、`024_product_size_prices.sql` → `030_product_size_prices.sql`、`025_menu_tea_milk.sql` → `031_menu_tea_milk.sql`。若某一步已用舊檔名套用，勿因改名重跑。`022_recipe_portions.sql` 是另一支配方升級檔，編號維持原樣。

014 加入 POS 職級。櫃台登入需要有效的 POS 帳號。啟用 demo 初始化時會建立缺少的 `staff`、`manager`、`pos`，使用 `pos` 在畫面上開班。

`scripts/` 裡的 Python 腳本只連本機 `127.0.0.1:3306`，不能用於雲端資料庫。

007 重跑防護的本機回歸測試：`python scripts/test_workbench_migration.py --local-config <本機設定檔路徑>`。設定檔須包含本機 `spring.datasource.username/password`；不可傳 Aiven 設定。腳本僅在 127.0.0.1 建立並清除自己建立的隨機測試庫，驗證首次升級、重跑不改部分退款／未付款資料，以及部分升級時安全停止。

不使用 DROP、TRUNCATE、INSERT IGNORE 或覆蓋既有資料。資料庫已存在時第一支會失敗；請停止，不要選擇忽略錯誤繼續執行。
種子資料僅執行一次。重複執行會因唯一鍵失敗，必須停止並 rollback，不要跳過錯誤；不可單獨重跑最後的庫存 UPDATE。
MySQL 的 DDL 不具整批回滾能力；若建表中途失敗，先檢查已完成的表，不要直接重跑或刪除資料庫。

## 目前資料約定

- 一張 `pos_order` 包含多筆 `order_item`；每筆為同商品、杯型、冰甜、加料、備註及製作狀態，`qty` 可大於 1。
- 同筆明細一起取消或轉損耗。只取消其中一杯的功能，需先拆分明細及其加料，尚未實作。
- `unit_price` 是含所有加料的每杯最終價（已扣掉首份免費的加料，046）。明細金額 = `qty * unit_price`；`price_at_order` 僅核對加價，不得再加一次。
- 飲品色系 `product.color_tone`（032）：RED、ORANGE、YELLOW、GREEN、PURPLE、WHITE、CREAM、BROWN（紅橙黃綠紫白奶咖；BROWN 是 036 加的），NULL＝前端依名稱、分類自動判斷；只存色系，色碼在前端。
- 飲品價格依容量分開記：`product.price_m`（中杯）、`price_l`（大杯）、`price_bottle`（瓶），NULL＝不賣這個容量（030）。同一種加料可以加多份，存成同一筆、`qty` 記份數（039）；每種的上限記在 `topping.max_qty`，一杯全部加料最多 5 份（040）。
- 珍奶配方已含一份珍珠；若額外選珍珠，需再扣一份，並再加價。
- 002 的舊配方 JSON 分中杯、大杯記錄原料和用量。026 封存為 `recipe_json_legacy`；現行配方使用 `product_recipe`，製作步驟使用 `recipe_steps`。
- `conflict_rule` JSON 目前只是明確範例，尚未有規則引擎，沒有啟用空泛的群組互斥規則。
- 依 spec2，會員只有一組常用地址，不保留歷史配送地址；盤點只留最近一次，沒有審核表及歷史表；沒有配方查看紀錄。
- JSON 內原料 ID 無法由一般外鍵保護，寫入時需要後端驗證，刪除原料前也需檢查。
- 額外加入商品及加料名稱快照、品項備註、訂單建立時間與編號、開班狀態、現金實收與找零，支援基本查詢與核對，不新增資料表。
- 所有金額為新台幣元，`DECIMAL(10,2)` 最多兩位小數；時間使用台灣本地時間，`duration` 為秒、`distance` 為公尺（最多兩位小數）。
- `order_item_topping.order_topping_id` 為獨立自增主鍵；`(item_id,topping_id)` 仍有唯一限制，避免同種加料重複。
- [目前 ER 圖](../docs/database-er.mmd) 包含後端必要欄位及修正後的可空白關聯。001 是歷史基礎版本，最新結構要套用 004 與 005。
- 資料庫本身沒有扣料觸發器；目前後端已在交易中完成計價、扣料、回補與 PIN 驗證，並用 requestId 與內容雜湊防止重複送單。正式日結 API 尚未實作。
- 訂單與明細的狀態一致性、總額及規則仍需後端驗證；SQL CHECK 只保護基本範圍與欄位組合。

## 假資料

全部為虛構店家與會員。5 種飲料：紅茶、綠茶、奶茶、珍奶、芒果冰沙；2 種加料：珍珠、茶凍。
8 種原料、4 條衝突規則、1 位會員、2 個員工參照及一筆固定 `2026-10-28` 的開班資料。
員工 `active=false`，密碼為不可登入的停用標記，不提供共用明碼密碼。這些只供訂單關聯使用；登入及 PIN 日後由帳號模組建立正式雜湊。
固定開班資料不是每天自動開班。另有選用的本機 demo initializer 建立 `pos_cashier`、`pos_manager` 與缺少的當日測試開班，見 API 文件；原始兩個停用帳號不變。

| 訂單 | 情境 | 金額 |
| --- | --- | --- |
| DEMO-20261028-001 | 大杯紅茶加珍珠兩杯（每杯 40）＋中杯奶茶加茶凍一杯（50）；現金 200 找 70 | 130 |
| DEMO-20261028-002 | 中杯芒果冰沙一杯，製作中 | 55 |
| DEMO-20261028-003 | 中杯綠茶一杯，未製作取消，已全額退款並回補 | 0 |
| DEMO-20261028-004 | 大杯珍奶一杯，已製作棄單，假設已全額退款；不回補，照片選填 | 0 |

基礎假資料的取消案例假設已全退；007 後 total 只表示保留品項合計，淨收款改用 paid_amount − refunded_amount，見 ../docs/PAYMENT_AND_WASTAGE.md。
007 支援取消時部分／全額退款或不退款，以及未付款接單；仍非完整金流帳務，不支援日結後退款。
現金樣本僅有正常交易；取消案例使用 LINE Pay 標記，沒有假設實際串接 LINE Pay。
運費欄位目前均為 0，不預設建議運費一定向客人收取。

測試庫存已扣除未取消訂單及已製作損耗，不扣未製作取消的綠茶。
預期庫存依 ID：98、97、96、100、98、97、99、7；冰沙料低於安全庫存可測紅燈。
預期保留營收 185 元，保留銷售 4 杯、未製作取消 1 杯、已製作損耗 1 杯。

## 後續串接前要確認

部分杯數取消的拆分方式、含加料單價、付款與退款定義、庫存不足是否阻擋、訂單狀態與日結規則，以及組員是否接受 JSON 格式與新增欄位。
POS API 使用 Boot 3／Gradle，見 API 文件。
現金 API 取消後 `change_cash` 為原找零加上人工退款的累計退還現金，`received_cash` 保留原實收；原始假資料的現金單沒有退款。

## 本機驗證（2026-10-28）

已在 MySQL 8.0.46 建立 `drinkshop_pos` 並依序執行三支 SQL。
確認 10 張 InnoDB 表、5 種飲料、2 種加料、8 種原料、4 條規則及 4 張訂單。
四張訂單金額全數一致，配方 JSON 的原料參照有效。
另以配方、明細杯數及加料獨立重算庫存，與資料庫相同。
六項無效寫入測試（零杯數、熱飲有冰、損耗無授權人、不存在的加料、找零錯誤、負庫存）均被約束拒絕，測試交易沒有提交。
SQL 僅以獨立的 POS 資料庫為目標。

## 團隊 ER 圖調整（2026-10-28）

已實際套用 005；既有資料逐筆比對一致，10 張表保留。
金額改為 DECIMAL(10,2)，Java 改用 BigDecimal。資料庫有 005 前的完整 mysqldump 備份（存於忽略的 build/db-backups）。
原始整數假資料保留，實際 MySQL 測試庫另驗證 40.30 × 3 = 120.90、找零 79.35 及退款 200.25，不改寫本機假商品價格。

成本假資料、計算範例與限制見 [DEMO_COSTS.md](../docs/DEMO_COSTS.md)。可執行唯讀的 `recipe_cost_report.sql` 檢查每杯配方分解。現有資料庫仍為 10 張表，沒有調整售價或庫存。

本機已備份並套用 007 收款與損耗欄位，現有訂單筆數與庫存保留。升級說明見 [PAYMENT_AND_WASTAGE.md](../docs/PAYMENT_AND_WASTAGE.md)。

組員啟動與 007 升級步驟請見 [TEAM_QUICKSTART.md](../docs/TEAM_QUICKSTART.md)。目前已製作棄單不需店長帳號或 PIN；approved_by 記錄有效登入操作員工，舊假資料仍保留歷史值。

## 原料報廢修正（尚未套用共用庫）

`009_raw_material_wastage.sql` 新增原料損耗事件表，共用庫已建立，`application.properties` 預設啟用寫入。連到沒有這張表的資料庫時，請在 `.pos-local.properties` 設 `pos.raw-wastage.enabled=false`；設定本身不會自動建表。行為及啟用步驟見 [CLOSING_WASTAGE_FIX.md](../docs/CLOSING_WASTAGE_FIX.md)。

## 原料盤點紀錄（尚未套用共用庫）

`016_inventory_count.sql` 新增盤點紀錄表 `inventory_count`：每次盤點每項原料一列，保存當時的帳面數量、實點數量、差異與單位成本。同一支檔案也在 `inventory` 加上 `allow_decimal`（預設 TRUE），控制盤點時小單位能不能填小數，例如紙杯可設成只能填整數：`UPDATE inventory SET allow_decimal = FALSE WHERE name IN ('中杯紙杯', '大杯紙杯');`。盤點只記錄，不修改 `inventory.book_qty`；`inventory.reported_qty`／`reported_by`／`reported_at` 仍會更新成最新一次。

共用庫由資料庫負責人確認升級。盤點預設啟用，依賴完整的盤點結構。若設 `pos.inventory-count.enabled=false`，盤點送出回 503。

## 盤點紀錄改成表頭／明細（017）

`017_inventory_count_header.sql`：016 的 `inventory_count` 每項原料一列，同一次盤點的盤點人、時間、備註重複存。017 改成表頭 `inventory_count`（一次盤點一列）＋明細 `inventory_count_line`（每項原料一列），並把現有資料依 `request_id` 搬過去；舊表改名為 `inventory_count_016` 保留，核對筆數無誤後再手動 `DROP`（檔案最後有核對用的查詢）。

同一支檔案也把 `inventory.allow_decimal` 預設改成 FALSE，並把現有原料都改成不能填小數；需要小數的原料再個別 `UPDATE inventory SET allow_decimal = TRUE WHERE ...`。

**後端程式依 017 的結構寫入與查詢，017 執行前盤點頁會無法送出與查詢紀錄。** 請先執行 017，再部署這版後端。

## 帳面庫存規則（2026-11 起）

點餐、取消訂單、原料報廢都**不再扣或回補** `inventory.book_qty`。配方用量一定和實際有落差，帳面改以盤點為準：

- 盤點送出時，帳面改成實點數量；盤點紀錄保留盤點前的帳面與差異。
- 手動校正（`PUT /api/inventories/{id}/stock`）仍可直接改帳面。
- 點餐不再檢查原料夠不夠；訂單明細仍記錄配方的理論用量（`deducted_inventory_json`）與原料成本（`material_cost`），報廢損耗金額照算。
- 原料報廢只留紀錄（數量、原因、當時成本），不扣帳面。

不需要改資料表。

## 盤點類型（018）

`018_inventory_count_type.sql` 新增盤點類型：盤點時先選類型，類型決定**輸入方式**（`PACK_AND_LOOSE` 箱數＋零散／`PACK_ONLY` 只填箱數）與**要盤哪些原料**（勾「全部原料」的類型會自動包含之後新增的原料）。

- 預設只建「大盤」（全部原料、箱數＋零散）；小盤等其他類型由店長、總部在盤點頁新增。
- 類型可以停用，不能刪除；停用後不能再用來盤點，舊紀錄不受影響。
- 盤點表頭 `inventory_count` 加上 `type_id`、`type_name`、`input_mode`，保存當時的類型名稱與輸入方式；017 之前的紀錄歸為大盤。
- 只填箱數時，有包裝單位的原料記成「箱數 × 每箱數量」，零頭不計；**同樣會覆蓋帳面**。

需要先執行 016、017。**這版後端需要 018 才能盤點**，請先執行 018 再部署。

## 大單位＋小單位顯示、芒果假資料（019）

`019_inventory_display_by_pack.sql` 在 `inventory` 加上 `display_by_pack`（預設 FALSE）。設成 TRUE 的原料，畫面上的帳面、實點、差異、報廢數量改成「幾籃幾顆」顯示，資料庫照樣存基本單位數量（`per_pack` 換算後）。盤點時零散數量要少於 1 個包裝單位。

`per_pack` 建議填實際的平均數量，原料成本與差異金額才算得準。同一支檔案加入假資料「芒果」：1 籃 = 30 顆、帳面 153 顆（5 籃 3 顆）、安全庫存 60 顆（2 籃）、每顆 15 元。

**這版後端需要 019（`Inventory` 會讀 `display_by_pack`），請先執行 019 再部署。**

## 盤點一律整數（020）

`020_count_integer_only.sql` 拿掉 `inventory.allow_decimal`。盤點都是數包裝或數得出的個數，開封、用到一半的不算，所以實點數量一律只收整數，不再逐項設定。原料報廢、手動校正仍可填小數（報廢不改帳面，只是紀錄與損耗金額）。

需要先執行 016–019。這版後端不再讀 `allow_decimal`，020 執行前後都能運作。

## 配方改成材料清單、半成品（021）

`021_recipe_tables.sql`：

- **配方只記「用了哪些材料」**（`product_recipe`）。份量與做法寫在 `product.recipe_steps`。舊的 JSON 封存在 `product.recipe_json_legacy`，供核對舊杯型與份量。
- **半成品** `prep_item`（例如茶凍、紅茶湯）與它用了哪些原料 `prep_item_ingredient`。配方的每一列是原料或半成品其中一個。
- **加料** `topping` 可以指向原料或半成品；拿掉扣料量 `deduct_qty`。
- 原料「茶凍」改成半成品，配方與加料的茶凍都改指向半成品；原料「茶凍」若有盤點或報廢紀錄則保留（見檔案最後的核對查詢）。
- **點餐和配方分開**：點餐不讀配方，沒有配方的飲品照樣可以點；每杯不再計算原料成本（`order_item.material_cost` 之後為 NULL，舊訂單保留）。日結的損耗金額只加總原料報廢，飲品報廢只算杯數。
- `recipe_cost_report.sql` 已停用。

搬資料時會解包多一層引號的配方，列出有資料的杯型共用的原料。只有一種杯型的飲品會保留該杯型全部原料，請用核對查詢檢查紙杯。需要 009、016–020。部署新版前請完成下節的配方升級。

材料清單維護範例（SQL）：

```sql
-- 新增半成品「紅茶湯」，由紅茶包做成
INSERT INTO prep_item (name, steps) VALUES ('紅茶湯', '每桶 10 包茶包，煮 8 分鐘');
INSERT INTO prep_item_ingredient (prep_id, inventory_id)
SELECT p.prep_id, i.inventory_id FROM prep_item p, inventory i WHERE p.name = '紅茶湯' AND i.name = '紅茶包';
-- 茶凍拿鐵：紅茶湯（半成品）＋牛奶（原料）＋茶凍（半成品）
INSERT INTO product_recipe (product_id, prep_id, sort_order) SELECT 6, prep_id, 1 FROM prep_item WHERE name = '紅茶湯';
INSERT INTO product_recipe (product_id, inventory_id, sort_order) SELECT 6, inventory_id, 2 FROM inventory WHERE name = '牛奶';
INSERT INTO product_recipe (product_id, prep_id, sort_order) SELECT 6, prep_id, 3 FROM prep_item WHERE name = '茶凍';
```


## 配方材料與份數升級（021–022）

配方編輯版後端需要 product_recipe.portions。由資料庫負責人備份並暫停後端，在 Workbench 同一個連線執行 [`026_recipe_upgrade_workbench.sql`](026_recipe_upgrade_workbench.sql) 整份檔案。帳號須有 `CREATE ROUTINE`、`ALTER ROUTINE`、`EXECUTE`、`CREATE TEMPORARY TABLES` 及遷移所需的表結構和資料權限。

- 尚未跑 021 時，合併檔會依序完成兩步。已完成 021 時，只補上份數欄位。已完成 022 時，會保留現有資料。
- 保留的 021 和 022_recipe_portions 個別檔適用於分開升級的流程。同一次升級選合併檔或個別檔其中一種。
- 出錯時停在第一個錯誤，先核對實際結構。MySQL 的 DDL 會逐語句提交。若錯誤後留下 `migrate_recipe_021_022`，完成核對後可清除該暫用程序。

份數以每項材料的獨立欄位儲存，舊配方份數留空。店長與總部帳號可在配方頁補登份數並修改製作步驟。若用 SQL 修改同一份配方，請在交易中先鎖定對應的 `product` 資料列，以和網站編輯使用同一把鎖。

027 依 `002_demo_data.sql`，將五款示範飲品的八筆材料空白份數填為 1。已填寫的份數與其他配方材料保留原值。

## 停售、分類、容量價格（028～031）

> 這四支原本編號 022～025，2026-11-16 合併 `codex/team-integration` 時改號（和組員的 `022_recipe_portions.sql` 撞號），內容沒有改。**已經執行過的不用再跑。**
>
> 共用庫狀態：`product` 已經沒有 `base_price`，代表 030（以及它之前的 028、029）已套用；031（新增 16 杯飲品）請資料庫負責人用下面的查詢確認。這版後端需要 028～030 都完成。
>
> ```sql
> SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
>  WHERE TABLE_SCHEMA = 'drinkshop_pos'
>    AND ((TABLE_NAME IN ('inventory', 'prep_item') AND COLUMN_NAME IN ('on_sale', 'category'))
>      OR (TABLE_NAME = 'product' AND COLUMN_NAME IN ('price_m', 'base_price')));   -- 028、029、030
> SELECT COUNT(*) FROM product WHERE name IN ('錫蘭奶茶', '紅茶拿鐵', '蕎麥綠寶石');  -- 031：3＝已套用
> ```

### 停售管理、原料連動停售（028）

`028_sale_status.sql`：`inventory`、`prep_item` 加上 `on_sale`（預設 TRUE）。原料或半成品停售時，材料清單或加料用到它的飲品、加料一起不能賣（點餐菜單不顯示、送單回 409）；半成品用到的原料停售也算半成品停售。飲品、加料自己的 `on_sale` 不會被改寫，原料恢復販售後會自動恢復。需要先執行 021。 API 見 [docs/SALE_STATUS.md](../docs/SALE_STATUS.md)。

### 原料、半成品分類（029）

`029_material_category.sql`：`inventory`、`prep_item` 加上 `category`（文字，NULL＝未分類），停售管理頁依分類選擇。檔案裡會依名稱先分好一次（原料：包材、茶葉、底料、水果；半成品：茶湯、底料、鮮果），分不出來的留 NULL，請用檔案最後的核對查詢檢查。之後由店長、總部在停售管理頁修改。需要先執行 028。

### 容量價格（030）、新增菜單飲品（031）

`030_product_size_prices.sql`：每杯飲品分別記三種容量的價格 `price_m`（中杯）、`price_l`（大杯）、`price_bottle`（瓶），NULL＝這杯不賣這個容量，至少要有一種。舊飲品照原價搬過去（中杯＝`base_price`，大杯＝`base_price + large_extra`），之後刪掉 `base_price`、`large_extra`；`order_item.size` 多一個 `BOTTLE`。需要先執行 029。

`031_menu_tea_milk.sql`：新增門市菜單的奶茶 3 杯、鮮奶 4 杯、茶類 9 杯（含蕎麥綠寶石），大杯＝菜單 L 價、瓶＝菜單瓶裝價，沒有中杯。可重複執行（同名已存在就略過）。先不建配方材料。

維護範例：

```sql
-- 調價、開放中杯
UPDATE product SET price_l = 55, price_m = 45 WHERE name = '錫蘭奶茶';
-- 不賣瓶裝
UPDATE product SET price_bottle = NULL WHERE name = '錫蘭奶茶';
```

## 飲品色系（032）

`032_product_color_tone.sql`：`product` 加上 `color_tone`，依顏色區分（ENUM）：`RED` 紅（紅茶色）、`ORANGE` 橙、`YELLOW` 黃、`GREEN` 綠、`PURPLE` 紫、`WHITE` 白、`CREAM` 奶；NULL＝未設定。點餐頁的飲品卡背景、杯子插圖依這個欄位配色；NULL 時前端照名稱、分類自動判斷（冰沙→YELLOW、奶茶→CREAM、名稱有「綠茶」→GREEN、其他→RED），所以執行後畫面不會變。

只存色系、不存色碼：一個色系有 4 個彼此搭配的顏色，色碼由前端統一管理。目前還沒有修改畫面，要設定請用 SQL：

```sql
UPDATE product SET color_tone = 'CREAM' WHERE name IN ('紅茶拿鐵', '鐵觀音拿鐵');
UPDATE product SET color_tone = NULL WHERE name = '紅茶拿鐵';   -- 改回自動判斷
```

新版後端的菜單 API 會讀這個欄位，**部署新版後端前一定要先執行 032**。需要先執行 031。如果之前執行過舊版 032（TEA、GREEN、MILK、FRUIT），改用檔案裡註解掉的轉換段落。

`033_tea_color_tone.sql`：先設定茶類的色系，依名稱：有「金萱」→黃、有「青茶／清茶／蕎麥」→橙、有「綠茶」→綠、其他茶類→紅。其他分類維持 NULL（前端自動判斷）。可重複執行，需要先執行 032。

## 其他分類商品（034）

`034_other_items.sql`：新增「其他」分類的購物袋（$1）、保冷袋（單）（$5）、保冷袋（雙）（$10）。只有一種價格，記在 `price_m`；`color_tone = 'WHITE'`。可重複執行，需要先執行 032。

分類就是 `product.category` 的文字，沒有另外的分類表：「特調」「芝芝」要等新增了那兩類的商品才會出現在點餐頁。

## 特調、芝芝飲品（035）

`035_menu_special_cheese.sql`：新增門市菜單的經典特調 3 杯（分類「特調」：梅子冰茶、梅子綠茶、養樂多綠茶）與芝芝系列 4 杯（分類「芝芝」：芝芝金萱、芝芝金萱雙Q、開心果椰水、開心果可可），價格照菜單的 M／L／瓶，沒有的容量是 NULL。可重複執行，需要先執行 030。先不建配方；梅子冰茶的「甜度冰量固定」還沒加規則。

## 咖色（036）

`036_color_tone_brown.sql`：`color_tone` 多一個 `BROWN`（咖）。已經執行過 032 的資料庫要再執行這支，才能填 BROWN。可重複執行。

## 菜單加料（037）

`037_menu_toppings.sql`：新增菜單「添加口感」的 10 種加料：+$10 波霸、椰果、雙Q果、波波、紅萱粉粿、綠茶凍、開心果脆脆；+$20 金絨芝芝、芝芝；+$35 開心果芝芝。每種加料要對應原料或半成品：有同名半成品就用半成品，否則用同名原料，都沒有就先建原料（單位「份」、帳面 0、分類「底料」）。可重複執行，需要先執行 029。「限冷飲」規則、和現有珍珠、茶凍的重複還沒處理。

## 溫度冰量、甜度（038）

`038_temp_ice_sugar.sql`：`order_item` 的溫度 `temperature`、冰量 `ice` 合成一個 `temp_ice`（HOT 熱、WARM 溫、ROOM 常溫、ICE_FREE 完全去冰、NO_ICE 去冰、LIGHT 微冰、LESS 少冰、NORMAL 正常冰、EXTRA 多冰）；甜度 `sugar` 改成 NONE 無糖、S0_5、S1、S3、S5、S7、S10（幾分糖）、EXTRA 多糖。舊訂單照對應換算（熱→HOT、溫→WARM、冷飲依冰量；正常糖→S10、少→S7、半→S5、微→S3）。

**這版後端需要 038，而且舊版後端在 038 之後無法送單**：038、新版後端、新版前端要一起上線。`conflict_rule` 不用改，舊規則會換算後比對；新規則可以寫 `tempIceIn`、`set.tempIce`。

## 加料份數（039）

`039_topping_qty.sql`：`order_item_topping` 加上 `qty`（份數，舊資料都是 1）。同一種加料可以加多份（上限見 040），存成同一筆、`qty` 記份數；`price_at_order` 是一份的價格。送單時 `toppingIds` 裡同一個 ID 出現兩次就是兩份。**這版後端需要 039**，需要先執行 038。

## 加料上限（040）

`040_topping_max_qty.sql`：`topping` 加上 `max_qty`（這種加料一杯最多幾份，1～5，預設 2）。一杯全部加料加起來最多 5 份（程式固定）。`conflict_rule` 裡的「加料最多三種」規則如果還在，會另外限制種類數，不需要就刪掉（檔案裡有查詢與刪除範例）。**這版後端需要 040**，需要先執行 039。

## 客製選項（041）

`041_custom_options.sql`：新增 `custom_option`（不用錢的客製選項：加酸、少料、加梅子、不加梅子；`max_qty` 一杯最多幾份、預設 1；`sort_order` 順序；`active` 停用）與 `order_item_option`（每杯選了哪些，存當時名稱與份數）。客製選項不用錢、沒有衝突規則、不扣原料。可重複執行，**這版後端需要 041**，需要先執行 040。

## 限冷飲加料規則（042）

`042_cold_only_toppings.sql`：新增一條 `conflict_rule`（FORBID）：熱、溫、常溫時不能加紅萱粉粿、綠茶凍（菜單標「限冷飲」）。用名稱找加料 ID，可重複執行；需要先執行 037、038。只要擋熱、溫的話，執行前把檔案裡的 `'ROOM'` 拿掉。

## 半成品用半成品做（043）

`043_prep_sub_prep.sql`：`prep_item_ingredient` 一列是半成品用到的一項材料，可以是原料（`inventory_id`）或另一個半成品（`sub_prep_id`，兩個只能填一個），`qty` 是做一份半成品的用量（原料用基本單位、半成品用份；舊資料都是 1）。主鍵改成流水號 `line_id`。例如雙Q果＝熟波霸（半成品，由生波霸做成）0.5 份＋椰果 0.5。

- 停售連動：由每項材料的 `link` 決定（044）。
- 消耗報表：半成品照用量展開成原料、一層層相乘；加料是半成品的（例如茶凍、雙Q果）以前不算消耗，現在也會算。
- 消耗報表最多往下追 3 層，更深的不算；不要建繞圈（A 用 B、B 又用 A）。目前只能用 SQL 設定半成品的材料，檔案最後有雙Q果的範例。

**這版後端需要 043**，只能執行一次，需要先執行 028。

## 材料停售連動（044）

`044_prep_ingredient_link.sql`：`prep_item_ingredient` 加上 `link`（預設 TRUE）。

- `TRUE`：這項材料停售時，半成品跟著不能賣；材料是半成品的會一路往上連動（最多 3 層），例如生波霸 → 熟波霸 → 雙Q果。
- `FALSE`：這項材料停售不影響這個半成品。例如綠茶凍用綠茶（茶湯）做，但綠茶凍是事先做好的，綠茶賣完綠茶凍照賣，賣完再手動停售。

只影響停售，消耗報表照樣算到原料。停售管理頁的半成品會顯示「由 珍珠、茶凍（不連動） 做成」。目前只能用 SQL 設定，檔案裡有綠茶凍的範例。可重複執行，**這版後端需要 044**，需要先執行 043。

## 停售管理頁顯示（045）

`045_show_in_sale.sql`：`product`、`prep_item`、`inventory`、`topping` 加上 `show_in_sale`（預設 TRUE）。FALSE＝不出現在停售管理頁（例如紙杯、吸管這種不會停售的包材）。只是不顯示：停售連動照常，點餐菜單也不受影響。欄位不叫 `show`，因為 SHOW 是 MySQL 保留字。可重複執行，**這版後端需要 045**，需要先執行 028。

## 組合飲品、首份免費加料（046）

`046_free_topping_combo.sql`：`topping.free`（可以組合的加料，只放 10 元的料）、`product.main`（可以當組合底的飲品）、`order_item_topping.free_qty`（這筆加料有幾份免費）。

- 點餐頁的「奶茶」「鮮奶」分類，在每杯 main 飲品後面排出「{加料}{飲料}」（例如波霸紅茶拿鐵），顏色跟 main 飲品一樣。點了就是 main 飲品先加好一份那個加料，不會新增飲品資料；規則照常會鎖。
- 分類是奶茶、鮮奶的飲品，照加入順序第一份 free 加料不用錢（不管是不是從組合點的），第二份以後照原價。分類寫在後端 `OrderRequests.FREE_TOPPING_CATEGORIES`、前端 `FREE_TOPPING_CATEGORIES`。
- 這杯加料收的錢＝`price_at_order ×（qty − free_qty）`，已算在 `order_item.unit_price` 裡。

可重複執行，**這版後端需要 046**，需要先執行 039。

## 備料紀錄（050）

`050_prep_batch.sql`：新增 `prep_batch`，記錄誰（`made_by`）在什麼時候煮了哪一種半成品（`prep_id`）、幾份（`qty`），可加備註；`recorded_by` 是登記的帳號（POS 機登記時是 POS 帳號）。不扣原料帳面、沒有半成品庫存；物料消耗報表會把「份數 × 半成品材料裡的原料用量」算成原料消耗（材料是別的半成品不算）。點餐系統的「備料」頁：門市 POS 共用帳號登記時選煮的人，店員、店長用自己的帳號登記時記成自己，總部帳號不用。API 是 `/api/prep-batches`（見 docs/POS_API.md）。可重複執行，**這版後端需要 050**，需要先執行 021。
