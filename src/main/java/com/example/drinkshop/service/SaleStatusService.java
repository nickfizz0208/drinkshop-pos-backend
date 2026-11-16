package com.example.drinkshop.service;

import com.example.drinkshop.dto.SaleStatusDtos.*;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.SaleStatusRepository;
import com.example.drinkshop.repository.SaleStatusRepository.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.example.drinkshop.repository.PosRepository.flag;

/**
 * 停售管理（022）：飲品、加料、原料、半成品都可以停售。
 * 原料或半成品停售時，用到它的飲品與加料一起不能賣（點餐菜單不顯示、送單會擋）；
 * 飲品、加料自己的停售狀態不會被改寫，所以原料恢復販售後它們會自動恢復。
 */
@Service
public class SaleStatusService {
    private final SaleStatusRepository repo;
    public SaleStatusService(SaleStatusRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public SaleStatus status() {
        // 原料
        Map<Long, String> invName = new LinkedHashMap<>(), invCategory = new HashMap<>();
        Set<Long> invStopped = new HashSet<>();
        for (var r : repo.inventories()) {
            long id = id(r, "inventory_id");
            invName.put(id, (String) r.get("name"));
            invCategory.put(id, (String) r.get("category"));
            if (!flag(r, "on_sale")) invStopped.add(id);
        }
        // 半成品與它的原料
        Map<Long, String> prepName = new LinkedHashMap<>(), prepCategory = new HashMap<>();
        Set<Long> prepSelfStopped = new HashSet<>();
        Map<Long, List<Long>> prepInv = new HashMap<>();
        for (var r : repo.preps()) {
            long id = id(r, "prep_id");
            prepName.put(id, (String) r.get("name"));
            prepCategory.put(id, (String) r.get("category"));
            prepInv.put(id, new ArrayList<>());
            if (!flag(r, "on_sale")) prepSelfStopped.add(id);
        }
        for (var r : repo.prepIngredients()) prepInv.computeIfAbsent(id(r, "prep_id"), k -> new ArrayList<>()).add(id(r, "inventory_id"));
        // 飲品材料清單
        Map<Long, List<Long>> productInv = new HashMap<>(), productPrep = new HashMap<>();
        for (var r : repo.recipeLines()) {
            long pid = id(r, "product_id");
            if (r.get("inventory_id") != null) productInv.computeIfAbsent(pid, k -> new ArrayList<>()).add(id(r, "inventory_id"));
            if (r.get("prep_id") != null) productPrep.computeIfAbsent(pid, k -> new ArrayList<>()).add(id(r, "prep_id"));
        }

        // 一組材料（原料＋半成品）中讓它不能賣的名稱；同時記下每項原料、半成品被誰用到
        Map<Long, Set<Long>> invUsedByProduct = new HashMap<>(), invUsedByTopping = new HashMap<>();
        Map<Long, Set<Long>> prepUsedByProduct = new HashMap<>(), prepUsedByTopping = new HashMap<>();

        List<ProductStatus> products = new ArrayList<>();
        for (var r : repo.products()) {
            long pid = id(r, "product_id");
            List<Long> inv = productInv.getOrDefault(pid, List.of()), preps = productPrep.getOrDefault(pid, List.of());
            for (long i : inv) invUsedByProduct.computeIfAbsent(i, k -> new TreeSet<>()).add(pid);
            for (long x : preps) {
                prepUsedByProduct.computeIfAbsent(x, k -> new TreeSet<>()).add(pid);
                for (long i : prepInv.getOrDefault(x, List.of())) invUsedByProduct.computeIfAbsent(i, k -> new TreeSet<>()).add(pid);
            }
            var stoppedBy = stoppedBy(inv, preps, invName, invStopped, prepName, prepSelfStopped, prepInv);
            boolean onSale = flag(r, "on_sale");
            products.add(new ProductStatus(pid, (String) r.get("name"), (String) r.get("category"), onSale,
                onSale && stoppedBy.isEmpty(), List.copyOf(inv), List.copyOf(preps), stoppedBy));
        }

        List<ToppingStatus> toppings = new ArrayList<>();
        for (var r : repo.toppings()) {
            long tid = id(r, "topping_id");
            Long inv = r.get("inventory_id") == null ? null : id(r, "inventory_id");
            Long prep = r.get("prep_id") == null ? null : id(r, "prep_id");
            if (inv != null) invUsedByTopping.computeIfAbsent(inv, k -> new TreeSet<>()).add(tid);
            if (prep != null) {
                prepUsedByTopping.computeIfAbsent(prep, k -> new TreeSet<>()).add(tid);
                for (long i : prepInv.getOrDefault(prep, List.of())) invUsedByTopping.computeIfAbsent(i, k -> new TreeSet<>()).add(tid);
            }
            var stoppedBy = stoppedBy(inv == null ? List.<Long>of() : List.of(inv), prep == null ? List.<Long>of() : List.of(prep),
                invName, invStopped, prepName, prepSelfStopped, prepInv);
            boolean onSale = flag(r, "on_sale");
            toppings.add(new ToppingStatus(tid, (String) r.get("name"), onSale, onSale && stoppedBy.isEmpty(), inv, prep, stoppedBy));
        }

        List<InventoryStatus> inventories = new ArrayList<>();
        invName.forEach((id, name) -> inventories.add(new InventoryStatus(id, name, invCategory.get(id), !invStopped.contains(id),
            List.copyOf(invUsedByProduct.getOrDefault(id, Set.of())), List.copyOf(invUsedByTopping.getOrDefault(id, Set.of())))));
        List<PrepStatus> preps = new ArrayList<>();
        prepName.forEach((id, name) -> {
            var ingredients = prepInv.getOrDefault(id, List.of());
            boolean available = !prepSelfStopped.contains(id) && ingredients.stream().noneMatch(invStopped::contains);
            preps.add(new PrepStatus(id, name, prepCategory.get(id), !prepSelfStopped.contains(id), available, List.copyOf(ingredients),
                List.copyOf(prepUsedByProduct.getOrDefault(id, Set.of())), List.copyOf(prepUsedByTopping.getOrDefault(id, Set.of()))));
        });
        return new SaleStatus(inventories, preps, products, toppings);
    }

    /** 一次儲存多項停售／恢復；任一項找不到就整批不存。 */
    @Transactional
    public SaleStatus update(Update request) {
        int total = 0;
        total += apply(Kind.INVENTORY, request.inventories());
        total += apply(Kind.PREP, request.preps());
        total += apply(Kind.PRODUCT, request.products());
        total += apply(Kind.TOPPING, request.toppings());
        if (total == 0) throw ApiException.bad("沒有要變更的項目");
        return status();
    }

    /** 改一項原料或半成品的分類（023）。前後空白去掉，空白＝未分類。 */
    @Transactional
    public SaleStatus updateCategory(CategoryChange change) {
        String category = change.category() == null || change.category().isBlank() ? null : change.category().trim();
        Kind kind = change.kind() == MaterialKind.INVENTORY ? Kind.INVENTORY : Kind.PREP;
        if (repo.setCategory(kind, change.id(), category) == 0) throw ApiException.missing("找不到這個" + kind.label + "：" + change.id());
        return status();
    }

    private int apply(Kind kind, List<Change> changes) {
        if (changes == null || changes.isEmpty()) return 0;
        Set<Long> seen = new HashSet<>();
        for (var c : changes) {
            if (!seen.add(c.id())) throw ApiException.bad(kind.label + "重複：" + c.id());
            if (repo.setOnSale(kind, c.id(), c.onSale()) == 0) throw ApiException.missing("找不到這個" + kind.label + "：" + c.id());
        }
        return changes.size();
    }

    private static List<String> stoppedBy(List<Long> inv, List<Long> preps, Map<Long, String> invName, Set<Long> invStopped,
                                          Map<Long, String> prepName, Set<Long> prepSelfStopped, Map<Long, List<Long>> prepInv) {
        var names = new LinkedHashSet<String>();
        for (long i : inv) if (invStopped.contains(i)) names.add(invName.get(i));
        for (long x : preps) {
            if (prepSelfStopped.contains(x)) names.add(prepName.get(x));
            for (long i : prepInv.getOrDefault(x, List.of())) if (invStopped.contains(i)) names.add(invName.get(i));
        }
        return List.copyOf(names);
    }
    private static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
