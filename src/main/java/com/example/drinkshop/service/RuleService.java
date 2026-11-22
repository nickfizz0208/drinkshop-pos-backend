package com.example.drinkshop.service;

import com.example.drinkshop.dto.OrderRequests.Item;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 後端點餐防呆：前端會先調整選項，但送單時仍以這裡的驗證為準。 */
@Service
public class RuleService {
    private final PosRepository repo;
    private final ObjectMapper json;
    public RuleService(PosRepository repo,ObjectMapper json) { this.repo=repo; this.json=json; }
    // 相容資料庫驅動回傳的 JSON 字串／位元組，以及外層又包一層字串的舊資料。
    public JsonNode parse(Object value) {
        try {
            String text=value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
            JsonNode node=json.readTree(text);
            return node.isTextual()?json.readTree(node.asText()):node;
        } catch (Exception ex) { throw ApiException.conflict("資料庫中的配方或規則格式錯誤"); }
    }
    public String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Cannot encode JSON",ex); }
    }
    public void validate(Item item,Map<String,Object> product) {
        if (!PosRepository.flag(product,"on_sale")) throw ApiException.conflict("飲品已停售");
        if (PosRepository.flag(product,"sold_out")) throw ApiException.conflict("飲品已售完");
        // 溫度冰量合成一個欄位（038），溫熱飲本來就不會有冰量，只要檢查這杯能不能做溫熱飲
        if (item.tempIce().hotOrWarm() && !PosRepository.flag(product,"hot_allowed")) throw ApiException.bad("此飲料不提供溫熱飲");
        // 一杯的加料最多 5 份（040，全部加料的份數加起來）；每種加料自己的上限 topping.max_qty 在 OrderService 檢查
        if (item.toppingIds().size()>com.example.drinkshop.dto.OrderRequests.MAX_TOPPINGS_PER_CUP)
            throw ApiException.bad("一杯最多加 "+com.example.drinkshop.dto.OrderRequests.MAX_TOPPINGS_PER_CUP+" 份加料");
        // condition_json 決定何時套用；action_json 決定禁止、固定、上限或互斥的內容。
        // AUTO_ADJUST 在後端是檢查固定值是否正確，不會偷偷替前端改掉送單內容。
        for (var row:repo.rules()) {
            var condition=parse(row.get("condition_json"));
            if (!matches(condition,item,product)) continue;
            var action=parse(row.get("action_json"));
            String message=row.get("message").toString();
            switch (row.get("rule_type").toString()) {
                case "FORBID" -> {
                    if (!action.path("forbidToppingIds").isArray()) throw ApiException.conflict("禁用規則缺少加料清單");
                    for (var id:action.get("forbidToppingIds")) if (item.toppingIds().contains(id.asLong())) throw ApiException.bad(message);
                }
                case "AUTO_ADJUST" -> {
                    if (!action.path("set").isObject()) throw ApiException.conflict("自動調整規則缺少固定值");
                    var fields=action.get("set").fields();
                    while(fields.hasNext()) {
                        var field=fields.next();
                        if (!fixed(field.getKey(),field.getValue().asText(),item)) throw ApiException.bad(message);
                    }
                }
                case "MAX_COUNT" -> {
                    if (!"toppingIds".equals(action.path("field").asText()) || !action.path("maxDistinct").canConvertToInt())
                        throw ApiException.conflict("加料上限規則格式錯誤");
                    // 上限算的是加料種類，同一種加兩份只算一種
                    if (item.toppingIds().stream().distinct().count()>action.get("maxDistinct").asInt()) throw ApiException.bad(message);
                }
                case "MUTEX" -> {
                    if (!action.path("toppingIds").isArray()) throw ApiException.conflict("互斥規則缺少加料群組");
                    int count=0;
                    for (var id:action.get("toppingIds")) if(item.toppingIds().contains(id.asLong())) count++;
                    if(count>1) throw ApiException.bad(message);
                }
                default -> throw ApiException.conflict("不支援的衝突規則");
            }
        }
    }
    // 同一個條件物件內的條件必須全部符合，才會啟用該條規則。
    private boolean matches(JsonNode condition,Item item,Map<String,Object> product) {
        if (!condition.isObject() || condition.isEmpty()) throw ApiException.conflict("規則條件不可空白");
        boolean matches=true;
        var fields=condition.fields();
        while(fields.hasNext()) {
            var field=fields.next();
            switch(field.getKey()) {
                case "always" -> matches &= field.getValue().asBoolean();
                case "category" -> matches &= field.getValue().asText().equals(product.get("category"));
                // 舊規則：溫度 COLD/WARM/HOT（常溫、各種冰量都算 COLD）
                case "temperatureIn" -> {
                    if(!field.getValue().isArray()) throw ApiException.conflict("溫度規則格式錯誤");
                    boolean found=false;
                    for(var v:field.getValue()) found |= v.asText().equals(item.tempIce().legacyTemperature());
                    matches &= found;
                }
                // 新規則：直接寫溫度冰量（038）
                case "tempIceIn" -> {
                    if(!field.getValue().isArray()) throw ApiException.conflict("溫度冰量規則格式錯誤");
                    boolean found=false;
                    for(var v:field.getValue()) found |= v.asText().equals(item.tempIce().name());
                    matches &= found;
                }
                default -> throw ApiException.conflict("不支援的規則條件："+field.getKey());
            }
        }
        return matches;
    }
    // 固定選項有沒有符合。新規則寫 tempIce、sugar；舊規則的 temperature、ice、舊甜度值換算後比對。
    private boolean fixed(String field,String expected,Item item) {
        return switch(field) {
            case "tempIce" -> item.tempIce().name().equals(expected);
            case "temperature" -> item.tempIce().legacyTemperature().equals(expected);
            // 溫熱飲本來就不加冰，舊的「固定冰量」只檢查冷飲
            case "ice" -> item.tempIce().hotOrWarm() || item.tempIce().legacyIce().equals(expected);
            case "sugar" -> {
                var sugar=com.example.drinkshop.dto.OrderRequests.Sugar.fromRule(expected);
                if (sugar==null) throw ApiException.conflict("不支援的甜度："+expected);
                yield item.sugar()==sugar;
            }
            default -> throw ApiException.conflict("不支援的固定選項："+field);
        };
    }
}
