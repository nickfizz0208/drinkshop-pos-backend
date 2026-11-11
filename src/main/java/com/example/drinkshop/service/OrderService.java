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

/**
 * 訂單的主要業務流程。閱讀順序：create 建單 → changeStatus 製作／取餐 → cancel 取消。
 * Controller 負責接 HTTP 請求，這裡負責計價、庫存、退款與狀態，repo 負責執行 SQL。
 * 帶有 @Transactional 的方法會把資料庫操作放在同一筆交易中：失敗時一起回滾。
 */
@Service
public class OrderService {
    private final PosRepository repo;
    private final RuleService rules;
    private final Clock clock;
    public OrderService(PosRepository repo,RuleService rules,Clock clock) {
        this.repo=repo; this.rules=rules; this.clock=clock;
    }
    // 計價後的暫存明細：price 是「含加料的單杯售價」。
    private record Priced(Item item,Map<String,Object> product,List<Map<String,Object>> toppings,BigDecimal price) {}

    // 操作人從已驗證的登入帳號取得，不能讓前端自行指定員工 ID。
    public Staff actor(String username) {
        return repo.staff(username).filter(Staff::active)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,"員工帳號已停用"));
    }

    /** 建立多杯訂單：檢查開班與規則 → 後端計價 → 檢查庫存 → 存訂單與明細 → 扣料。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Create request,String username) {
        Staff staff=actor(username);
        // 同一次送單重試沿用 requestId；若先前已成功，回傳原單，避免多建單、多扣料。
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
        // 同營業日的訂單異動先鎖開班資料，依序處理；等鎖期間可能已建單，所以再查一次。
        existing=repo.rows("SELECT * FROM pos_order WHERE order_no=?",orderNo);
        if (!existing.isEmpty()) return replay(existing.getFirst(),hash,staff.id());
        if (request.memberPhone()!=null && repo.rows("SELECT phone FROM member WHERE phone=?",request.memberPhone()).isEmpty())
            throw ApiException.missing("找不到此會員電話，請先建立會員");
        if (request.payment()!=Payment.CASH && request.receivedCash()!=null) throw ApiException.bad("非現金付款不需要實收現金");

        var priced=new ArrayList<Priced>();
        BigDecimal total=Money.ZERO;
        // 一張訂單有多筆 Item；每筆可有多杯，但共用杯型、冰甜、加料與備註。
        for (Item item:request.items()) {
            var product=repo.product(item.productId());
            rules.validate(item,product);
            BigDecimal price=Money.from(product,"base_price").add(item.size()==Size.L?Money.from(product,"large_extra"):Money.ZERO);
            var toppings=new ArrayList<Map<String,Object>>();
            for (long id:item.toppingIds()) {
                var topping=repo.topping(id);
                if (!flag(topping,"on_sale")) throw ApiException.conflict("加料已下架");
                price=price.add(Money.from(topping,"price"));
                toppings.add(topping);
            }
            price=Money.checked(price);
            total=Money.checked(total.add(price.multiply(BigDecimal.valueOf(item.qty()))));
            priced.add(new Priced(item,product,toppings,price));
        }
        if (request.receivedCash()!=null) Money.checked(request.receivedCash());
        if (request.payment()==Payment.CASH && (request.receivedCash()==null || request.receivedCash().compareTo(total)<0))
            throw ApiException.bad("實收現金不足");
        // 點餐和配方分開：不讀配方、不扣帳面庫存（帳面以盤點為準），也不檢查庫存夠不夠；沒有配方的飲品照樣可以點。
        // 配方只記「用了哪些材料」，沒有份量，所以每杯不計算原料成本（deducted_inventory_json、material_cost 留空，舊訂單保留原值）。
        LocalDateTime now=LocalDateTime.now(clock);
        long orderId=repo.insert("order_id", """
            INSERT INTO pos_order(order_no,business_date,member_phone,source,payment,total,received_cash,change_cash,
                status,distance,duration,address_note,note,created_by,created_at,request_hash,payment_status,paid_amount)
            VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?)
            """,orderNo,date,request.memberPhone(),request.source().name(),request.payment()==null?null:request.payment().name(),total,
            request.receivedCash(),request.payment()==Payment.CASH?request.receivedCash().subtract(total):null,
            request.distance(),request.duration(),request.addressNote(),request.note(),staff.id(),now,hash,
            request.unpaid()?"UNPAID":"PAID",request.unpaid()?Money.ZERO:total);
        // 存下當時的品名與售價；以後改菜單，不回頭改歷史訂單。
        for(Priced line:priced) {
            Item item=line.item();
            long itemId=repo.insert("item_id", """
                INSERT INTO order_item(order_id,product_id,product_name,size,temperature,sugar,ice,qty,unit_price,status,note)
                VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?)
                """,orderId,item.productId(),line.product().get("name"),item.size().name(),item.temperature().name(),
                item.sugar().name(),item.ice().name(),item.qty(),line.price(),item.note());
            for(var topping:line.toppings()) repo.update("INSERT INTO order_item_topping(item_id,topping_id,topping_name,price_at_order) VALUES (?,?,?,?)",
                itemId,topping.get("topping_id"),topping.get("name"),topping.get("price"));
        }
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
        // 固定欄位順序與舊名稱，讓舊版送單重試的雜湊仍能比對；金額格式差異會先統一。
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
    @Transactional(isolation=Isolation.READ_COMMITTED)
    // 替「先接單、稍後付款」的舊訂單補收款，只記錄店員確認的結果，沒有呼叫金流平台。
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
    // 日結後禁止改單；鎖定順序統一為「開班 → 訂單 → 明細／庫存」。
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
    // 單筆明細只能向前：待製作 → 製作中 → 完成待取 → 已取餐；取消另走 cancel。
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
    // 整單操作只推進符合上一階段的明細；整單取餐還要求已收款且沒有未完成飲品。
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
        // 已取消、已損耗或已走到後面階段的明細，不會被整單按鈕倒退或覆寫。
        for(var item:items) if(previous.equals(item.get("status")))
            repo.update("UPDATE order_item SET status=?,updated_at=? WHERE item_id=?",next.name(),LocalDateTime.now(clock),item.get("item_id"));
        refresh(orderId);
        return repo.detail(orderId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    // itemId 為 null 表示整單；有值則取消該明細的全部杯數，目前不拆同筆中的單杯。
    // 尚未製作：轉 CANCELLED；已開始製作：轉 WASTED。點餐不扣帳面庫存，所以取消也不回補。
    public Map<String,Object> cancel(long orderId,Long itemId,Cancel request,String username) {
        Staff staff=actor(username); var order=lockOrder(orderId);
        var items=repo.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY item_id FOR UPDATE",orderId);
        if(itemId!=null) items=items.stream().filter(i->number(i,"item_id")==itemId).toList();
        if(items.isEmpty()) throw ApiException.missing("找不到訂單明細");
        var active=items.stream().filter(i->!Set.of("CANCELLED","WASTED").contains(i.get("status"))).toList();
        if(active.isEmpty()) return repo.detail(orderId); // 重試已取消項目時直接回原單，不再回補或退款。
        if(active.stream().anyMatch(i->"PICKED_UP".equals(i.get("status"))))
            throw ApiException.conflict("已取餐訂單的退款不在本版操作範圍");
        BigDecimal cancelledValue=active.stream().map(i->Money.from(i,"unit_price").multiply(BigDecimal.valueOf(integer(i,"qty")))).reduce(Money.ZERO,BigDecimal::add);
        // 退款上限同時受「本次取消售價」與「已收款扣掉已退款」限制；未付款自然只能退 0。
        BigDecimal available=Money.from(order,"paid_amount").subtract(Money.from(order,"refunded_amount"));
        BigDecimal maximum=cancelledValue.min(available);
        BigDecimal refund=request.refundAmount()==null?maximum:Money.checked(request.refundAmount());
        if(refund.compareTo(maximum)>0) throw ApiException.bad("退款不能超過本次取消品項金額或尚未退還的已收款");
        boolean needsManager=active.stream().anyMatch(i->!"PENDING".equals(i.get("status")));
        // needsManager 是舊變數名；目前已取消 PIN 審核，approved_by 記錄實際操作員工。
        Long approved=needsManager?staff.id():null;
        for(var item:active) {
            boolean pending="PENDING".equals(item.get("status"));
            repo.update("UPDATE order_item SET status=?,reason=?,approved_by=?,updated_at=? WHERE item_id=?",
                pending?"CANCELLED":"WASTED",request.reason().trim(),pending?null:approved,LocalDateTime.now(clock),item.get("item_id"));
        }
        repo.update("UPDATE pos_order SET refunded_amount=refunded_amount+?,change_cash=CASE WHEN payment='CASH' THEN change_cash+? ELSE NULL END WHERE order_id=?",refund,refund,orderId);
        // 將本次退款依明細順序分配，方便追查；只記錄已由店員人工完成的退款。
        BigDecimal remaining=refund;
        for(var item:active) {
            BigDecimal portion=remaining.min(Money.from(item,"unit_price").multiply(BigDecimal.valueOf(integer(item,"qty"))));
            repo.update("UPDATE order_item SET refund_amount=? WHERE item_id=?",portion,item.get("item_id"));
            remaining=remaining.subtract(portion);
        }
        refresh(orderId);
        return repo.detail(orderId);
    }
    /** 新報廢入口只接受製作中／待取餐；預設不退款，退款必須明確傳入。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> recordWastage(long itemId, Cancel request, String username) {
        actor(username);
        var before=repo.one("SELECT order_id FROM order_item WHERE item_id=?",itemId);
        long orderId=number(before,"order_id");
        lockOrder(orderId);
        var item=repo.one("SELECT status FROM order_item WHERE item_id=? FOR UPDATE",itemId);
        if (!Set.of("MAKING","DONE","WASTED").contains(item.get("status")))
            throw ApiException.conflict("只有製作中或待取餐品項可報廢；待製作請走取消，已取餐不支援報廢");
        return cancel(orderId,itemId,request,username);
    }

    // 依仍保留的明細重算總額與整單狀態；取消的明細仍保存在資料庫，沒有刪除。
    // total 是保留品項金額；淨收款要算 paid_amount - refunded_amount，兩者不能混用。
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
        // change_cash 保留累計退回現金（原找零＋人工退款），不代表只有最初的找零。
        BigDecimal returned="CASH".equals(order.get("payment"))?Money.from(order,"received_cash").subtract(Money.from(order,"paid_amount")).add(Money.from(order,"refunded_amount")):null;
        repo.update("UPDATE pos_order SET total=?,change_cash=?,status=?,updated_at=? WHERE order_id=?",total,returned,status,LocalDateTime.now(clock),orderId);
    }
}
