package com.example.drinkshop.service;

import com.example.drinkshop.dto.RecipeDtos.*;
import com.example.drinkshop.entity.Inventory;
import com.example.drinkshop.entity.Product;
import com.example.drinkshop.entity.StaffAccount;
import com.example.drinkshop.entity.StaffLevel;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.InventoryRepository;
import com.example.drinkshop.repository.ProductRepository;
import com.example.drinkshop.repository.StaffAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

// 手機端查看配方：所有個人帳號都看得到全部上架飲品（不分等級）。POS 共用帳號由 SecurityConfig 擋掉
// product.min_view_level 欄位目前不使用
@Service
public class RecipeService {
    private static final Map<String, String> SIZE_LABELS = Map.of("M", "中杯", "L", "大杯");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ProductRepository productRepo;
    private final InventoryRepository inventoryRepo;
    private final StaffAccountRepository staffRepo;
    private final ObjectMapper json;

    public RecipeService(ProductRepository productRepo, InventoryRepository inventoryRepo,
                         StaffAccountRepository staffRepo, ObjectMapper json) {
        this.productRepo = productRepo; this.inventoryRepo = inventoryRepo; this.staffRepo = staffRepo; this.json = json;
    }

    // 列表＋搜尋＋分類篩選。keyword 會去掉前後空白，空字串當作沒有篩選
    public List<RecipeSummary> list(String username, String keyword, String category) {
        var staff = staff(username);
        String kw = blankToNull(keyword), cat = blankToNull(category);
        if (kw != null && kw.length() > 50) throw ApiException.bad("搜尋字最多 50 個字");
        if (cat != null && cat.length() > 30) throw ApiException.bad("分類名稱最多 30 個字");
        return productRepo.search(visibleLevels(staff), kw, cat).stream()
            .map(p -> new RecipeSummary(p.productId(), p.name(), p.category(), parse(p.recipeJson()).status()))
            .toList();
    }

    public List<String> categories(String username) { return productRepo.categories(visibleLevels(staff(username))); }

    public RecipeDetail detail(String username, long productId) {
        var staff = staff(username);
        // 不存在、已下架：404
        var p = productRepo.findById(productId).filter(Product::onSale)
            .orElseThrow(() -> ApiException.missing("找不到這個飲品"));
        // 帳號等級是不認得的值（拼錯、空白）：403
        if (staff.level().isEmpty()) throw ApiException.forbidden("你的帳號不能查看配方");

        var parsed = parse(p.recipeJson());
        // 原料一次全部查出來，避免每一行原料各查一次資料庫
        var inventory = inventoryRepo.findAll().stream().collect(Collectors.toMap(Inventory::getInventoryId, Function.identity()));
        List<RecipeSize> sizes = new ArrayList<>();
        for (var entry : parsed.sizes().entrySet()) {
            List<RecipeLine> lines = new ArrayList<>();
            for (var raw : entry.getValue()) {
                var inv = inventory.get(raw.inventoryId());
                String unit = inv == null || inv.getBaseUnit() == null || inv.getBaseUnit().isBlank() ? null : inv.getBaseUnit();
                lines.add(new RecipeLine(raw.inventoryId(), inv == null ? "未知原料（編號 " + raw.inventoryId() + "）" : inv.getName(),
                    raw.qty(), unit));
            }
            sizes.add(new RecipeSize(entry.getKey(), SIZE_LABELS.getOrDefault(entry.getKey(), entry.getKey()), lines));
        }
        String watermark = staff.name() + " " + staff.username() + " " + LocalDateTime.now(TAIPEI).format(TIME);
        return new RecipeDetail(p.productId(), p.name(), p.category(), parsed.status(), true, sizes,
            blankToNull(p.recipeSteps()), watermark);
    }

    // ---- 讀 recipe_json：{"M":[{"qty":1,"inventoryId":1}], "L":[...]} ----
    private record RawLine(long inventoryId, java.math.BigDecimal qty) {}
    private record Parsed(RecipeStatus status, Map<String, List<RawLine>> sizes) {}

    // 只讀資料、不換算：qty 照資料原樣（BigDecimal 不失真）。看不懂的格式標成 INVALID，不硬湊
    private Parsed parse(String recipeJson) {
        if (recipeJson == null || recipeJson.isBlank()) return new Parsed(RecipeStatus.MISSING, Map.of());
        JsonNode root;
        try {
            root = json.readTree(recipeJson);
            // 團隊資料有些配方多包了一層引號（存成字串），和 RuleService.parse 一樣再拆一次
            if (root != null && root.isTextual()) root = json.readTree(root.asText());
        } catch (Exception e) {
            return new Parsed(RecipeStatus.INVALID, Map.of());
        }
        if (root == null || !root.isObject()) return new Parsed(RecipeStatus.INVALID, Map.of());
        // 杯型順序固定：中杯、大杯，其他代碼照字母排
        List<String> keys = new ArrayList<>();
        root.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparingInt((String k) -> k.equals("M") ? 0 : k.equals("L") ? 1 : 2).thenComparing(k -> k));
        Map<String, List<RawLine>> sizes = new LinkedHashMap<>();
        for (String key : keys) {
            JsonNode arr = root.get(key);
            if (!arr.isArray()) return new Parsed(RecipeStatus.INVALID, Map.of());
            if (arr.isEmpty()) continue;
            List<RawLine> lines = new ArrayList<>();
            for (JsonNode line : arr) {
                if (!line.path("inventoryId").canConvertToLong() || !line.path("qty").isNumber())
                    return new Parsed(RecipeStatus.INVALID, Map.of());
                lines.add(new RawLine(line.path("inventoryId").asLong(), line.path("qty").decimalValue()));
            }
            sizes.put(key, lines);
        }
        return sizes.isEmpty() ? new Parsed(RecipeStatus.MISSING, Map.of()) : new Parsed(RecipeStatus.OK, sizes);
    }

    // 不分等級：所有 min_view_level 的飲品都列出；帳號等級是不認得的值 → 空的（什麼都看不到）
    private List<String> visibleLevels(StaffAccount staff) {
        return staff.level().map(mine -> Arrays.stream(StaffLevel.values()).map(Enum::name).toList())
            .orElse(List.of());
    }
    private StaffAccount staff(String username) {
        return staffRepo.findByUsername(username).orElseThrow(() -> ApiException.unauthorized("請重新登入"));
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
