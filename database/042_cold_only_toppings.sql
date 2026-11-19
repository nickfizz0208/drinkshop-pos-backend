-- 042：菜單標「限冷飲」的加料（紅萱粉粿、綠茶凍）在熱、溫、常溫時不能加
-- 僅由資料庫負責人確認後執行；需要先執行 037（新增這兩種加料）與 038（溫度冰量）。可以重複執行：同一條規則已經存在就略過。
--
-- 點餐頁會依這條規則把這兩種加料的按鈕鎖住；先加了再切成熱、溫、常溫，會自動移除。送單時後端也會再擋一次。
-- 用名稱找出加料 ID 寫進規則；之後加料改名不影響已經建好的規則。
-- 只要擋熱、溫（常溫可以加）的話，把下面的 'ROOM' 拿掉。

INSERT INTO conflict_rule (rule_type, condition_json, action_json, message)
SELECT 'FORBID',
       JSON_OBJECT('tempIceIn', JSON_ARRAY('HOT', 'WARM', 'ROOM')),
       JSON_OBJECT('forbidToppingIds', JSON_ARRAYAGG(t.topping_id)),
       '紅萱粉粿、綠茶凍限冷飲'
FROM topping t
WHERE t.name IN ('紅萱粉粿', '綠茶凍')
HAVING COUNT(*) > 0
   AND NOT EXISTS (SELECT 1 FROM conflict_rule r WHERE r.message = '紅萱粉粿、綠茶凍限冷飲');

-- 核對：
-- SELECT rule_id, rule_type, condition_json, action_json, message, enabled FROM conflict_rule ORDER BY rule_id;
