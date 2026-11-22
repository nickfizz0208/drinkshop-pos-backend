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
 * 停售管理（028）：飲品、加料、原料、半成品都可以停售。
 * 原料或半成品停售時，用到它的飲品與加料一起不能賣（點餐菜單不顯示、送單會擋）；
 * 飲品、加料自己的停售狀態不會被改寫，所以原料恢復販售後它們會自動恢復。
 * 半成品的材料可以是別的半成品（043）；每項材料的 link（044）決定它停售時要不要連動，link＝TRUE 會一路往上連動。
 */
@Service
public class SaleStatusService {
    private final SaleStatusRepository repo;
    public SaleStatusService(SaleStatusRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public SaleStatus status() {
        // 原料
        Map<Long, String> invName = new LinkedHashMap<>(), invCategory = new HashMap<>();
        Set<Long> invStopped = new HashSet<>(), invHidden = new HashSet<>();
        for (var r : repo.inventories()) {
            long id = id(r, "inventory_id");
            if (!flag(r, "show_in_sale")) invHidden.add(id);
            invName.put(id, (String) r.get("name"));
            invCategory.put(id, (String) r.get("category"));
            if (!flag(r, "on_sale")) invStopped.add(id);
        }
        // 半成品與它的原料
        Map<Long, String> prepName = new LinkedHashMap<>(), prepCategory = new HashMap<>();
        Set<Long> prepSelfStopped = new HashSet<>(), prepHidden = new HashSet<>();
        Map<Long, List<Long>> prepInv = new HashMap<>(), prepSub = new HashMap<>();          // 全部材料（顯示用）
        Map<Long, List<Long>> linkInv = new HashMap<>(), linkSub = new HashMap<>();          // link＝TRUE 的材料（停售連動用，044）
        Map<Long, List<PrepIngredient>> prepIngredients = new HashMap<>();
        for (var r : repo.preps()) {
            long id = id(r, "prep_id");
            prepName.put(id, (String) r.get("name"));
            prepCategory.put(id, (String) r.get("category"));
            prepInv.put(id, new ArrayList<>());
            if (!flag(r, "on_sale")) prepSelfStopped.add(id);
            if (!flag(r, "show_in_sale")) prepHidden.add(id);
        }
        // 材料可以是原料或別的半成品（043）；link（044）決定它停售時這個半成品要不要跟著停
        for (var r : repo.prepIngredients()) {
            long prep = id(r, "prep_id");
            boolean link = flag(r, "link");
            Long inv = r.get("inventory_id") == null ? null : id(r, "inventory_id");
            Long sub = r.get("sub_prep_id") == null ? null : id(r, "sub_prep_id");
            if (inv != null) {
                prepInv.computeIfAbsent(prep, k -> new ArrayList<>()).add(inv);
                if (link) linkInv.computeIfAbsent(prep, k -> new ArrayList<>()).add(inv);
            }
            if (sub != null) {
                prepSub.computeIfAbsent(prep, k -> new ArrayList<>()).add(sub);
                if (link) linkSub.computeIfAbsent(prep, k -> new ArrayList<>()).add(sub);
            }
            prepIngredients.computeIfAbsent(prep, k -> new ArrayList<>()).add(new PrepIngredient(inv, sub, link));
        }
        // 每個半成品順著 link＝TRUE 往下展開（最多 MAX_SUB_PREP_LEVELS 層）：哪些原料、半成品停售會讓它不能賣
        Map<Long, Set<Long>> prepAllInv = new HashMap<>(), prepAllSub = new HashMap<>();
        for (long x : prepName.keySet()) {
            Set<Long> inv = new LinkedHashSet<>(), sub = new LinkedHashSet<>();
            expand(x, 0, linkInv, linkSub, inv, sub);
            sub.remove(x);
            prepAllInv.put(x, inv);
            prepAllSub.put(x, sub);
        }
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
                for (long s : prepAllSub.getOrDefault(x, Set.of())) prepUsedByProduct.computeIfAbsent(s, k -> new TreeSet<>()).add(pid);
                for (long i : prepAllInv.getOrDefault(x, Set.of())) invUsedByProduct.computeIfAbsent(i, k -> new TreeSet<>()).add(pid);
            }
            var stoppedBy = stoppedBy(inv, preps, invName, invStopped, prepName, prepSelfStopped, prepAllInv, prepAllSub);
            boolean onSale = flag(r, "on_sale");
            products.add(new ProductStatus(pid, (String) r.get("name"), (String) r.get("category"), onSale,
                onSale && !flag(r, "sold_out") && stoppedBy.isEmpty(), List.copyOf(inv), List.copyOf(preps), stoppedBy, flag(r, "show_in_sale"), flag(r, "sold_out")));
        }

        List<ToppingStatus> toppings = new ArrayList<>();
        for (var r : repo.toppings()) {
            long tid = id(r, "topping_id");
            Long inv = r.get("inventory_id") == null ? null : id(r, "inventory_id");
            Long prep = r.get("prep_id") == null ? null : id(r, "prep_id");
            if (inv != null) invUsedByTopping.computeIfAbsent(inv, k -> new TreeSet<>()).add(tid);
            if (prep != null) {
                prepUsedByTopping.computeIfAbsent(prep, k -> new TreeSet<>()).add(tid);
                for (long s : prepAllSub.getOrDefault(prep, Set.of())) prepUsedByTopping.computeIfAbsent(s, k -> new TreeSet<>()).add(tid);
                for (long i : prepAllInv.getOrDefault(prep, Set.of())) invUsedByTopping.computeIfAbsent(i, k -> new TreeSet<>()).add(tid);
            }
            var stoppedBy = stoppedBy(inv == null ? List.<Long>of() : List.of(inv), prep == null ? List.<Long>of() : List.of(prep),
                invName, invStopped, prepName, prepSelfStopped, prepAllInv, prepAllSub);
            boolean onSale = flag(r, "on_sale");
            toppings.add(new ToppingStatus(tid, (String) r.get("name"), onSale, onSale && stoppedBy.isEmpty(), inv, prep, stoppedBy,
                flag(r, "show_in_sale")));
        }

        List<InventoryStatus> inventories = new ArrayList<>();
        invName.forEach((id, name) -> inventories.add(new InventoryStatus(id, name, invCategory.get(id), !invStopped.contains(id),
            List.copyOf(invUsedByProduct.getOrDefault(id, Set.of())), List.copyOf(invUsedByTopping.getOrDefault(id, Set.of())),
            !invHidden.contains(id))));
        List<PrepStatus> preps = new ArrayList<>();
        prepName.forEach((id, name) -> {
            boolean available = !prepSelfStopped.contains(id)
                && prepAllInv.getOrDefault(id, Set.of()).stream().noneMatch(invStopped::contains)
                && prepAllSub.getOrDefault(id, Set.of()).stream().noneMatch(prepSelfStopped::contains);
            preps.add(new PrepStatus(id, name, prepCategory.get(id), !prepSelfStopped.contains(id), available,
                List.copyOf(prepInv.getOrDefault(id, List.of())), List.copyOf(prepSub.getOrDefault(id, List.of())),
                List.copyOf(prepIngredients.getOrDefault(id, List.of())),
                List.copyOf(prepUsedByProduct.getOrDefault(id, Set.of())), List.copyOf(prepUsedByTopping.getOrDefault(id, Set.of())),
                !prepHidden.contains(id)));
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
        if (request.soldOutProducts() != null) {
            Set<Long> seen = new HashSet<>();
            for (var change : request.soldOutProducts()) {
                if (!seen.add(change.id())) throw ApiException.bad("飲品售完設定重複：" + change.id());
                if (repo.setSoldOut(change.id(), change.soldOut()) == 0) throw ApiException.missing("找不到這個飲品：" + change.id());
                total++;
            }
        }
        if (total == 0) throw ApiException.bad("沒有要變更的項目");
        return status();
    }

    /** 改一項原料或半成品的分類（029）。前後空白去掉，空白＝未分類。 */
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
                                          Map<Long, String> prepName, Set<Long> prepSelfStopped,
                                          Map<Long, Set<Long>> prepAllInv, Map<Long, Set<Long>> prepAllSub) {
        var names = new LinkedHashSet<String>();
        for (long i : inv) if (invStopped.contains(i)) names.add(invName.get(i));
        for (long x : preps) {
            if (prepSelfStopped.contains(x)) names.add(prepName.get(x));
            for (long s : prepAllSub.getOrDefault(x, Set.of())) if (prepSelfStopped.contains(s)) names.add(prepName.get(s));
            for (long i : prepAllInv.getOrDefault(x, Set.of())) if (invStopped.contains(i)) names.add(invName.get(i));
        }
        return List.copyOf(names);
    }

    /** 順著 link＝TRUE 的材料把半成品往下展開（最多 MAX_SUB_PREP_LEVELS 層）；seenPreps 也用來避免繞圈。 */
    private static void expand(long prep, int level, Map<Long, List<Long>> linkInv, Map<Long, List<Long>> linkSub,
                               Set<Long> invOut, Set<Long> seenPreps) {
        if (!seenPreps.add(prep)) return;
        invOut.addAll(linkInv.getOrDefault(prep, List.of()));
        if (level >= SaleStatusRepository.MAX_SUB_PREP_LEVELS) return;
        for (long sub : linkSub.getOrDefault(prep, List.of())) expand(sub, level + 1, linkInv, linkSub, invOut, seenPreps);
    }
    private static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
