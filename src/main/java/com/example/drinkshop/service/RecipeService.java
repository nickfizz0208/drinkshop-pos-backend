package com.example.drinkshop.service;

import com.example.drinkshop.dto.RecipeDtos.*;
import com.example.drinkshop.entity.Product;
import com.example.drinkshop.entity.StaffAccount;
import com.example.drinkshop.entity.StaffLevel;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.ProductRepository;
import com.example.drinkshop.repository.StaffAccountRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

// 手機端查看配方：所有個人帳號都看得到全部上架飲品（不分等級）。POS 共用帳號由 SecurityConfig 擋掉
// product.min_view_level 欄位目前不使用
@Service
public class RecipeService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ProductRepository productRepo;
    private final StaffAccountRepository staffRepo;

    public RecipeService(ProductRepository productRepo, StaffAccountRepository staffRepo) {
        this.productRepo = productRepo; this.staffRepo = staffRepo;
    }

    // 列表＋搜尋＋分類篩選。keyword 會去掉前後空白，空字串當作沒有篩選
    public List<RecipeSummary> list(String username, String keyword, String category) {
        var staff = staff(username);
        String kw = blankToNull(keyword), cat = blankToNull(category);
        if (kw != null && kw.length() > 50) throw ApiException.bad("搜尋字最多 50 個字");
        if (cat != null && cat.length() > 30) throw ApiException.bad("分類名稱最多 30 個字");
        var withRecipe = productRepo.productIdsWithRecipe();
        return productRepo.search(visibleLevels(staff), kw, cat).stream()
            .map(p -> new RecipeSummary(p.productId(), p.name(), p.category(),
                withRecipe.contains(p.productId()) ? RecipeStatus.OK : RecipeStatus.MISSING))
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

        // 材料清單（product_recipe）：只有「用了什麼」，份量寫在 steps
        List<RecipeMaterial> materials = new ArrayList<>();
        for (var r : productRepo.materials(p.productId())) {
            boolean prep = r.get("prep_id") != null;
            String madeFrom = (String) r.get("made_from");
            materials.add(new RecipeMaterial(prep ? MaterialKind.PREP : MaterialKind.INVENTORY,
                ((Number) r.get(prep ? "prep_id" : "inventory_id")).longValue(), String.valueOf(r.get("name")),
                prep && madeFrom != null ? List.of(madeFrom.split("、")) : List.of()));
        }
        String watermark = staff.name() + " " + staff.username() + " " + LocalDateTime.now(TAIPEI).format(TIME);
        return new RecipeDetail(p.productId(), p.name(), p.category(),
            materials.isEmpty() ? RecipeStatus.MISSING : RecipeStatus.OK, true, materials,
            blankToNull(p.recipeSteps()), watermark);
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
