package com.example.drinkshop.service;

import com.example.drinkshop.dto.OrderRequests.*;
import com.example.drinkshop.entity.Staff;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.PosRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.example.drinkshop.repository.PosRepository.*;

@Service
public class OrderService {
    private final PosRepository repo;
    private final RuleService rules;
    private final Clock clock;
    public OrderService(PosRepository repo,RuleService rules,Clock clock) {
        this.repo=repo; this.rules=rules; this.clock=clock;
    }
    private record Priced(Item item,Map<String,Object> product,List<Map<String,Object>> toppings,
                          BigDecimal price,Map<Long,BigDecimal> deducted) {}

    public Staff actor(String username) {
        return repo.staff(username).filter(Staff::active)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,"員工帳號已停用"));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Create request,String username) {
        Staff staff=actor(username);
        String orderNo=request.requestId().toString();
        String hash=hashRequest(request);
        var existing=repo.rows("SELECT * FROM pos_order WHERE order_no=?",orderNo);
        if (!existing.isEmpty()) return replay(existing.getFirst(),hash,staff.id());
        if (request.payment()==Payment.UBER_EATS)
            throw ApiException.bad("付款方式請選擇現金、信用卡或 LINE Pay；Uber Eats 請設為訂單來源");
        if (request.unpaid() && (request.payment()!=null || request.receivedCash()!=null))
            throw ApiException.bad("稍後收款的訂單不能填付款方式或實收現金");
        if (!request.unpaid() && request.payment()==null) throw ApiException.bad("請選擇付款方式");
        LocalDate date=LocalDate.now(clock);
        lockShift(date);
        // All writers for one business day serialize here, including concurrent retries.
        existing=repo.rows("SELECT * FROM pos_order WHERE order_no=?",orderNo);
        if (!existing.isEmpty()) return replay(existing.getFirst(),hash,staff.id());
        if (request.memberPhone()!=null) repo.one("SELECT phone FROM member WHERE phone=?",request.memberPhone());
        if (request.payment()!=Payment.CASH && request.receivedCash()!=null) throw ApiException.bad("非現金付款不需要實收現金");

        var priced=new ArrayList<Priced>();
        var allDeductions=new TreeMap<Long,BigDecimal>();
        BigDecimal total=Money.ZERO;
        for (Item item:request.items()) {
            var product=repo.product(item.productId());
            rules.validate(item,product);
            BigDecimal price=Money.from(product,"base_price").add(item.size()==Size.L?Money.from(product,"large_extra"):Money.ZERO);
            var toppings=new ArrayList<Map<String,Object>>();
            var deduction=recipe(product,item);
            for (long id:item.toppingIds()) {
                var topping=repo.topping(id);
                if (!flag(topping,"on_sale")) throw ApiException.conflict("加料已下架");
                price=price.add(Money.from(topping,"price"));
                add(deduction,number(topping,"inventory_id"),new BigDecimal(topping.get("deduct_qty").toString()).multiply(BigDecimal.valueOf(item.qty())));
                toppings.add(topping);
            }
            price=Money.checked(price);
            total=Money.checked(total.add(price.multiply(BigDecimal.valueOf(item.qty()))));
            deduction.forEach((id,qty)->add(allDeductions,id,qty));
            priced.add(new Priced(item,product,toppings,price,deduction));
        }
        if (request.receivedCash()!=null) Money.checked(request.receivedCash());
        if (request.payment()==Payment.CASH && (request.receivedCash()==null || request.receivedCash().compareTo(total)<0))
            throw ApiException.bad("實收現金不足");
        // Stable inventory lock order prevents overselling and reduces deadlocks.
        for(var deduction:allDeductions.entrySet()) {
            var stock=repo.one("SELECT book_qty FROM inventory WHERE inventory_id=? FOR UPDATE",deduction.getKey());
            if(new BigDecimal(stock.get("book_qty").toString()).compareTo(deduction.getValue())<0)
                throw ApiException.conflict("原料庫存不足，請盤點或調整點餐內容");
        }
        LocalDateTime now=LocalDateTime.now(clock);
        long orderId=repo.insert("order_id", """
            INSERT INTO pos_order(order_no,business_date,member_phone,source,payment,total,received_cash,change_cash,
                status,distance,duration,address_note,note,created_by,created_at,request_hash,payment_status,paid_amount)
            VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?)
            """,orderNo,date,request.memberPhone(),request.source().name(),request.payment()==null?null:request.payment().name(),total,
            request.receivedCash(),request.payment()==Payment.CASH?request.receivedCash().subtract(total):null,
            request.distance(),request.duration(),request.addressNote(),request.note(),staff.id(),now,hash,
            request.unpaid()?"UNPAID":"PAID",request.unpaid()?Money.ZERO:total);
        for(Priced line:priced) {
            Item item=line.item();
            long itemId=repo.insert("item_id", """
                INSERT INTO order_item(order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,note,deducted_inventory_json,material_cost)
                VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)
                """,orderId,item.productId(),line.product().get("name"),item.size().name(),item.temperature().name(),
                item.sugar().name(),item.ice().name(),item.qty(),line.price(),item.note(),rules.write(line.deducted()),materialCost(line.deducted()));
            for(var topping:line.toppings()) repo.update("INSERT INTO order_item_topping(item_id,topping_id,topping_name,price_at_order) VALUES (?,?,?,?)",
                itemId,topping.get("topping_id"),topping.get("name"),topping.get("price"));
        }
        allDeductions.forEach((id,qty)->repo.update("UPDATE inventory SET book_qty=book_qty-? WHERE inventory_id=?",qty,id));
        return repo.detail(orderId);
    }

    private Map<String,Object> replay(Map<String,Object> order,String hash,long staffId) {
        if (!hash.equals(order.get("request_hash")) || number(order,"created_by")!=staffId)
            throw ApiException.conflict("此 requestId 已用於不同的訂單內容或員工，請勿重用");
        return repo.detail(number(order,"order_id"));
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private String hashRequest(Create request) {
        // Preserve the original key order/names so pre-migration request hashes still match.
        var fields=new LinkedHashMap<String,Object>();
        fields.put("requestId",request.requestId()); fields.put("source",request.source());
        fields.put("payment",request.payment()); fields.put("memberPhone",request.memberPhone());
        fields.put("receivedCash",Money.canonical(request.receivedCash()));
        fields.put("distanceM",Money.canonical(request.distance()));
        fields.put("durationSeconds",request.duration()); fields.put("addressNote",request.addressNote());
        fields.put("note",request.note()); fields.put("items",request.items());
        if(request.unpaid()) fields.put("unpaid",true);
        return hash(rules.write(fields));
    }
    private BigDecimal materialCost(Map<Long,BigDecimal> quantities) {
        BigDecimal cost=Money.ZERO;
        for(var entry:quantities.entrySet()) {
            var stock=repo.one("SELECT unit_cost FROM inventory WHERE inventory_id=?",entry.getKey());
            if(stock.get("unit_cost")==null) return null;
            cost=cost.add(Money.from(stock,"unit_cost").multiply(entry.getValue()));
        }
        return Money.checked(cost.setScale(2,java.math.RoundingMode.HALF_UP));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> collect(long id,Collect request,String username) {
        actor(username); var order=lockOrder(id);
        if(request.payment()==Payment.UBER_EATS) throw ApiException.bad("Uber Eats 不是付款方式");
        if("PAID".equals(order.get("payment_status"))) {
            if(request.payment().name().equals(order.get("payment")) &&
                Objects.equals(Money.canonical(request.receivedCash()),Money.canonical((BigDecimal)order.get("received_cash"))))
                return repo.detail(id);
            throw ApiException.conflict("此訂單已收款，請重新整理確認，不能重複收款");
        }
        if("CANCELLED".equals(order.get("status"))) throw ApiException.conflict("已取消的訂單不能收款");
        BigDecimal amount=Money.from(order,"total"),cash=request.receivedCash();
        if(request.payment()==Payment.CASH) {
            if(cash==null || Money.checked(cash).compareTo(amount)<0) throw ApiException.bad("實收現金不足");
        } else if(cash!=null) throw ApiException.bad("非現金付款不需要實收現金");
        repo.update("UPDATE pos_order SET payment_status='PAID',payment=?,paid_amount=?,received_cash=?,change_cash=?,updated_at=? WHERE order_id=?",
            request.payment().name(),amount,cash,cash==null?null:cash.subtract(amount),LocalDateTime.now(clock),id);
        return repo.detail(id);
    }
    private Map<Long,BigDecimal> recipe(Map<String,Object> product,Item item) {
        var recipe=rules.parse(product.get("recipe_json")).path(item.size().name());
        if (!recipe.isArray() || recipe.isEmpty()) throw ApiException.conflict("此杯型缺少扣料配方");
        var result=new TreeMap<Long,BigDecimal>();
        for(var ingredient:recipe) {
            if (!ingredient.path("inventoryId").canConvertToLong() || !ingredient.path("qty").isNumber())
                throw ApiException.conflict("配方缺少有效原料與數量");
            long id=ingredient.get("inventoryId").asLong();
            BigDecimal qty=ingredient.get("qty").decimalValue();
            if (id<=0 || qty.signum()<=0 || qty.stripTrailingZeros().scale()>2) throw ApiException.conflict("配方數量必須大於零且最多兩位小數");
            add(result,id,qty.multiply(BigDecimal.valueOf(item.qty())));
        }
        return result;
    }
    private void add(Map<Long,BigDecimal> quantities,long id,BigDecimal qty) {
        quantities.merge(id,qty,BigDecimal::add);
    }
    private void lockShift(LocalDate date) {
        var rows=repo.rows("SELECT * FROM daily_shift WHERE business_date=? FOR UPDATE",date);
        if(rows.isEmpty() || !"OPEN".equals(rows.getFirst().get("status")))
            throw ApiException.conflict("此營業日尚未開班或已日結，不能結帳或修改訂單");
    }
    private Map<String,Object> lockOrder(long id) {
        var before=repo.order(id);
        lockShift(LocalDate.parse(before.get("business_date").toString()));
        return repo.one("SELECT * FROM pos_order WHERE order_id=? FOR UPDATE",id);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> changeStatus(long orderId,long itemId,ItemStatus next,String username) {
        actor(username); var order=lockOrder(orderId);
        var item=repo.one("SELECT * FROM order_item WHERE order_id=? AND item_id=? FOR UPDATE",orderId,itemId);
        String previous=item.get("status").toString();
        if(previous.equals(next.name())) return repo.detail(orderId);
        boolean valid=(previous.equals("PENDING") && next==ItemStatus.MAKING)
            || (previous.equals("MAKING") && next==ItemStatus.DONE)
            || (previous.equals("DONE") && next==ItemStatus.PICKED_UP);
        if(!valid) throw ApiException.conflict("狀態只能依待製作、製作中、完成、已取餐順序推進；取消請使用取消 API");
        if(next==ItemStatus.PICKED_UP && "UNPAID".equals(order.get("payment_status")))
            throw ApiException.conflict("請先完成收款，再標記已取餐");
        repo.update("UPDATE order_item SET status=?,updated_at=? WHERE item_id=?",next.name(),LocalDateTime.now(clock),itemId);
        refresh(orderId);
        return repo.detail(orderId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> changeAllStatuses(long orderId,ItemStatus next,String username) {
        actor(username); var order=lockOrder(orderId);
        String previous=switch(next) {
            case MAKING -> "PENDING";
            case DONE -> "MAKING";
            case PICKED_UP -> "DONE";
            default -> throw ApiException.bad("整單操作只支援開始製作、製作完成或取餐");
        };
        var items=repo.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY item_id FOR UPDATE",orderId);
        if(next==ItemStatus.PICKED_UP) {
            if("UNPAID".equals(order.get("payment_status"))) throw ApiException.conflict("請先完成收款，再整單取餐");
            if(items.stream().anyMatch(i->Set.of("PENDING","MAKING").contains(i.get("status"))))
                throw ApiException.conflict("還有飲品尚未製作完成，不能整單取餐");
        }
        // Change only adjacent eligible lines; cancelled/wasted/advanced lines stay untouched.
        for(var item:items) if(previous.equals(item.get("status")))
            repo.update("UPDATE order_item SET status=?,updated_at=? WHERE item_id=?",next.name(),LocalDateTime.now(clock),item.get("item_id"));
        refresh(orderId);
        return repo.detail(orderId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> cancel(long orderId,Long itemId,Cancel request,String username) {
        Staff staff=actor(username); var order=lockOrder(orderId);
        var items=repo.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY item_id FOR UPDATE",orderId);
        if(itemId!=null) items=items.stream().filter(i->number(i,"item_id")==itemId).toList();
        if(items.isEmpty()) throw ApiException.missing("找不到訂單明細");
        var active=items.stream().filter(i->!Set.of("CANCELLED","WASTED").contains(i.get("status"))).toList();
        if(active.isEmpty()) return repo.detail(orderId); // Retry never restores twice.
        if(active.stream().anyMatch(i->"PICKED_UP".equals(i.get("status"))))
            throw ApiException.conflict("已取餐訂單的退款不在本版操作範圍");
        BigDecimal cancelledValue=active.stream().map(i->Money.from(i,"unit_price").multiply(BigDecimal.valueOf(integer(i,"qty")))).reduce(Money.ZERO,BigDecimal::add);
        BigDecimal available=Money.from(order,"paid_amount").subtract(Money.from(order,"refunded_amount"));
        BigDecimal maximum=cancelledValue.min(available);
        BigDecimal refund=request.refundAmount()==null?maximum:Money.checked(request.refundAmount());
        if(refund.compareTo(maximum)>0) throw ApiException.bad("退款不能超過本次取消品項金額或尚未退還的已收款");
        boolean needsManager=active.stream().anyMatch(i->!"PENDING".equals(i.get("status")));
        // Legacy approved_by now records the signed-in wastage operator, not manager approval.
        Long approved=needsManager?staff.id():null;
        var restores=new TreeMap<Long,BigDecimal>();
        for(var item:active) {
            boolean pending="PENDING".equals(item.get("status"));
            if(pending) {
                if(item.get("deducted_inventory_json")==null) throw ApiException.conflict("舊明細缺少扣料快照，請先核對原料再取消");
                var fields=rules.parse(item.get("deducted_inventory_json")).fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    add(restores,Long.parseLong(field.getKey()),field.getValue().decimalValue());
                }
            }
            repo.update("UPDATE order_item SET status=?,reason=?,approved_by=?,updated_at=? WHERE item_id=?",
                pending?"CANCELLED":"WASTED",request.reason().trim(),pending?null:approved,LocalDateTime.now(clock),item.get("item_id"));
        }
        for(var restore:restores.entrySet()) {
            repo.one("SELECT inventory_id FROM inventory WHERE inventory_id=? FOR UPDATE",restore.getKey());
            repo.update("UPDATE inventory SET book_qty=book_qty+? WHERE inventory_id=?",restore.getValue(),restore.getKey());
        }
        repo.update("UPDATE pos_order SET refunded_amount=refunded_amount+?,change_cash=CASE WHEN payment='CASH' THEN change_cash+? ELSE NULL END WHERE order_id=?",refund,refund,orderId);
        // Allocate a single cancellation refund to its lines for later audit, exactly once.
        BigDecimal remaining=refund;
        for(var item:active) {
            BigDecimal portion=remaining.min(Money.from(item,"unit_price").multiply(BigDecimal.valueOf(integer(item,"qty"))));
            repo.update("UPDATE order_item SET refund_amount=? WHERE item_id=?",portion,item.get("item_id"));
            remaining=remaining.subtract(portion);
        }
        refresh(orderId);
        return repo.detail(orderId);
    }
    private void refresh(long orderId) {
        var order=repo.order(orderId);
        var live=repo.items(orderId).stream().filter(i->!Set.of("CANCELLED","WASTED").contains(i.get("status"))).toList();
        BigDecimal total=live.isEmpty()?Money.ZERO:Money.from(order,"delivery_fee");
        for(var item:live) total=Money.checked(total.add(Money.from(item,"unit_price").multiply(BigDecimal.valueOf(integer(item,"qty")))));
        String status;
        if(live.isEmpty()) status="CANCELLED";
        else if(live.stream().allMatch(i->"PICKED_UP".equals(i.get("status")))) status="PICKED_UP";
        else if(live.stream().allMatch(i->Set.of("DONE","PICKED_UP").contains(i.get("status")))) status="DONE";
        else if(live.stream().allMatch(i->"PENDING".equals(i.get("status")))) status="PENDING";
        else status="MAKING";
        // Cash change is cumulative returned cash (initial change + manually returned refunds).
        BigDecimal returned="CASH".equals(order.get("payment"))?Money.from(order,"received_cash").subtract(Money.from(order,"paid_amount")).add(Money.from(order,"refunded_amount")):null;
        repo.update("UPDATE pos_order SET total=?,change_cash=?,status=?,updated_at=? WHERE order_id=?",total,returned,status,LocalDateTime.now(clock),orderId);
    }
}
