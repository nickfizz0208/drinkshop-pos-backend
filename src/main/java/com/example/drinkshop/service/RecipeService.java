package com.example.drinkshop.service;

import com.example.drinkshop.dto.RecipeDtos.*;
import com.example.drinkshop.entity.Product;
import com.example.drinkshop.entity.StaffAccount;
import com.example.drinkshop.entity.StaffLevel;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.ProductRepository;
import com.example.drinkshop.repository.StaffAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

// 櫃台與個人帳號可查看全部上架飲品配方，店長與總部可編輯。
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecipeDetail detail(String username, long productId) {
        var staff = staff(username);
        // 不存在、已下架：404
        var p = productRepo.findById(productId).filter(Product::onSale)
            .orElseThrow(() -> ApiException.missing("找不到這個飲品"));
        // 帳號等級是不認得的值（拼錯、空白）：403
        if (staff.level().isEmpty()) throw ApiException.forbidden("你的帳號不能查看配方");
        return detail(p, staff);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecipeDetail update(String username, long productId, UpdateRecipe update) {
        var editor = staff(username);
        var level = editor.level().orElse(null);
        if (!editor.active() || (level != StaffLevel.MANAGER && level != StaffLevel.HQ))
            throw ApiException.forbidden("只有店長及總部帳號可以編輯配方");
        var product = productRepo.findLocked(productId).filter(Product::onSale)
            .orElseThrow(() -> ApiException.missing("找不到這個飲品"));
        var current = detail(product, editor);

        Map<MaterialKey, MaterialPortions> requested = new HashMap<>();
        for (var line : update.materials()) {
            if (requested.putIfAbsent(new MaterialKey(line.kind(), line.id()), line) != null)
                throw ApiException.bad("材料不可重複");
        }
        List<MaterialPortions> desired = new ArrayList<>();
        for (var material : current.materials()) {
            var line = requested.remove(new MaterialKey(material.kind(), material.id()));
            if (line == null) throw ApiException.bad("請提供配方中每項材料的份數");
            desired.add(line);
        }
        if (!requested.isEmpty()) throw ApiException.bad("無法編輯配方以外的材料");

        String steps = update.steps().strip();
        if (current.version().equals(version(productId, steps, desired))) return current;
        if (!current.version().equals(update.version()))
            throw ApiException.conflict("配方已被更新，請重新開啟後再編輯");

        if (!Objects.equals(blankToNull(steps), current.steps())) productRepo.updateRecipeSteps(productId, steps);
        for (int i = 0; i < desired.size(); i++) {
            var line = desired.get(i);
            var before = current.materials().get(i).portions();
            if (Objects.equals(line.portions(), before)
                || (line.portions() != null && before != null && line.portions().compareTo(before) == 0)) continue;
            if (productRepo.updatePortions(productId, line.id(), line.kind() == MaterialKind.PREP, line.portions()) != 1)
                throw ApiException.conflict("配方材料已被更新，請重新開啟後再編輯");
        }
        return detail(productRepo.findById(productId).orElseThrow(), editor);
    }

    private RecipeDetail detail(Product p, StaffAccount staff) {
        List<RecipeMaterial> materials = new ArrayList<>();
        for (var r : productRepo.materials(p.productId())) {
            boolean prep = r.get("prep_id") != null;
            String madeFrom = (String) r.get("made_from");
            materials.add(new RecipeMaterial(prep ? MaterialKind.PREP : MaterialKind.INVENTORY,
                ((Number) r.get(prep ? "prep_id" : "inventory_id")).longValue(), String.valueOf(r.get("name")),
                prep && madeFrom != null ? List.of(madeFrom.split("、")) : List.of(),
                (BigDecimal) r.get("portions")));
        }
        String watermark = staff.name() + " " + staff.username() + " " + LocalDateTime.now(TAIPEI).format(TIME);
        var steps = blankToNull(p.recipeSteps());
        var portions = materials.stream()
            .map(material -> new MaterialPortions(material.kind(), material.id(), material.portions())).toList();
        return new RecipeDetail(p.productId(), p.name(), p.category(),
            materials.isEmpty() ? RecipeStatus.MISSING : RecipeStatus.OK, true, materials,
            steps, watermark, version(p.productId(), steps, portions));
    }

    private record MaterialKey(MaterialKind kind, long id) {}

    private static String version(long productId, String steps, List<MaterialPortions> portions) {
        var content = new StringBuilder().append(productId).append('|');
        for (var line : portions) {
            content.append(line.kind()).append(':').append(line.id()).append(':');
            if (line.portions() != null) content.append(line.portions().stripTrailingZeros().toPlainString());
            content.append('|');
        }
        if (steps != null) content.append(steps.strip());
        return DigestUtils.md5DigestAsHex(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    // 不分等級：所有 min_view_level 的飲品都列出；帳號等級是不認得的值 → 空的（什麼都看不到）
    private List<String> visibleLevels(StaffAccount staff) {
        return staff.level().map(mine -> Arrays.stream(StaffLevel.values()).map(Enum::name).toList())
            .orElse(List.of());
    }
    private StaffAccount staff(String username) {
        return staffRepo.findByUsername(username).orElseThrow(() -> ApiException.unauthorized("請重新登入"));
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
