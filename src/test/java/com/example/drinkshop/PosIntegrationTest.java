package com.example.drinkshop;

import com.example.drinkshop.dto.OrderRequests.*;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:pos;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "pos.photo-directory=build/test-photos"
})
@AutoConfigureMockMvc
class PosIntegrationTest {
    private static final String PASSWORD="Test-password-123";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired OrderService orders;

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"),ZoneId.of("Asia/Taipei"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("data-h2.sql")).execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'test_staff',?,'測試員工','STAFF',TRUE)",passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,pin_hash,active) VALUES (11,'test_manager',?,'測試店長','MANAGER',?,TRUE)",passwords.encode(PASSWORD),passwords.encode("654321"));
        Path photos=Path.of("build/test-photos");
        if(Files.isDirectory(photos)) try(var files=Files.list(photos)) {
            for(Path file:files.toList()) Files.deleteIfExists(file);
        }
    }
    private Item item(long product,int qty,List<Long> toppings) {
        return new Item(product,Size.M,Temperature.COLD,Sugar.HALF,Ice.LESS,qty,toppings,null);
    }
    private Create request(Item... items) {
        return new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.LINE_PAY,null,null,null,null,null,null,List.of(items));
    }
    private JsonNode create(Create request) throws Exception {
        return json.readTree(mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content(json.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private org.springframework.test.web.servlet.ResultActions cancel(long order,long item,String manager,String pin) throws Exception {
        return mvc.perform(post("/api/orders/"+order+"/items/"+item+"/cancel").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content(json.writeValueAsBytes(new Cancel("測試取消",manager,pin))));
    }
    private org.springframework.test.web.servlet.ResultActions state(long order,long item,ItemStatus next) throws Exception {
        return mvc.perform(post("/api/orders/"+order+"/items/"+item+"/status").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content(json.writeValueAsBytes(new ChangeStatus(next))));
    }
    private double stock(long id) { return jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=?",Double.class,id); }
    private int countOrders() { return jdbc.queryForObject("SELECT COUNT(*) FROM pos_order",Integer.class); }

    @Test void requiresAuthenticationAndRejectsDisabledAccounts() throws Exception {
        mvc.perform(get("/api/menu")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/menu").with(httpBasic("demo_staff","anything"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(httpBasic("test_staff",PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.staffId").value(10));
        jdbc.update("UPDATE staff_account SET active=FALSE WHERE staff_id=10");
        mvc.perform(get("/api/auth/me").with(httpBasic("test_staff",PASSWORD))).andExpect(status().isUnauthorized());
    }
    @Test void menuHidesRecipeAndPasswords() throws Exception {
        String body=mvc.perform(get("/api/menu").with(httpBasic("test_staff",PASSWORD)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.products.length()").value(5))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("recipe")); assertFalse(body.contains("password"));
    }
    @Test void rulesReturnStructuredJson() throws Exception {
        mvc.perform(get("/api/rules").with(httpBasic("test_staff",PASSWORD)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].condition_json.temperatureIn[0]").value("WARM"));
    }
    @Test void pricesIncludeToppingsAndMultiplyGroupedCups() throws Exception {
        double cups=stock(1),tea=stock(3),pearl=stock(6);
        var result=create(request(item(1,2,List.of(1L)),item(3,1,List.of(2L))));
        assertEquals(120,result.get("total").asInt());
        assertEquals(35,result.get("items").get(0).get("unit_price").asInt());
        assertEquals(2,result.get("items").size());
        assertEquals(cups-3,stock(1)); assertEquals(tea-3,stock(3)); assertEquals(pearl-2,stock(6));
        assertFalse(result.toString().contains("request_hash"));
    }
    @Test void repeatedRequestDoesNotDeductAgain() throws Exception {
        var request=request(item(1,2,List.of(1L)));
        var first=create(request); double remaining=stock(6);
        var second=create(request);
        assertEquals(first.get("order_id"),second.get("order_id"));
        assertEquals(remaining,stock(6)); assertEquals(5,countOrders());
    }
    @Test void changedPayloadCannotReuseRequestId() throws Exception {
        var first=request(item(1,1,List.of())); create(first);
        var changed=new Create(first.requestId(),first.source(),first.payment(),null,null,null,null,null,null,List.of(item(1,2,List.of())));
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(changed))).andExpect(status().isConflict());
        assertEquals(5,countOrders());
    }
    @Test void rejectsHotJellyAndInvalidSlushWithoutWriting() throws Exception {
        var hot=new Item(1L,Size.M,Temperature.HOT,Sugar.NORMAL,Ice.NONE,1,List.of(2L),null);
        for(var invalid:List.of(hot,item(5,1,List.of()))) {
            mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
                .content(json.writeValueAsBytes(request(invalid)))).andExpect(status().isBadRequest());
        }
        assertEquals(4,countOrders());
    }
    @Test void rejectsDuplicateToppingsAndZeroQuantity() throws Exception {
        for(var invalid:List.of(item(1,1,List.of(1L,1L)),item(1,0,List.of())))
            mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
                .content(json.writeValueAsBytes(request(invalid)))).andExpect(status().isBadRequest());
        assertEquals(4,countOrders());
    }
    @Test void missingOrClosedShiftBlocksCheckout() throws Exception {
        jdbc.update("UPDATE pos_order SET business_date='2026-10-28'");
        jdbc.update("UPDATE daily_shift SET status='CLOSED',closed_by=11,closed_at=CURRENT_TIMESTAMP,revenue_by_payment='{}',cups=0,expected_cash=2000,counted_cash=2000");
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(request(item(1,1,List.of()))))).andExpect(status().isConflict());
        assertEquals(4,countOrders());
    }
    @Test void stockShortageRollsBackWholeOrder() throws Exception {
        double before=stock(1); jdbc.update("UPDATE inventory SET book_qty=0 WHERE inventory_id=6");
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(request(item(1,1,List.of()),item(3,1,List.of(1L)))))).andExpect(status().isConflict());
        assertEquals(before,stock(1)); assertEquals(4,countOrders());
    }
    @Test void lateInsertFailureRollsBackMainAndEarlierDetail() throws Exception {
        double before=stock(1);
        jdbc.execute("ALTER TABLE order_item ADD CONSTRAINT test_second_item_failure CHECK (product_id<>2 OR status='CANCELLED')");
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(request(item(1,1,List.of()),item(2,1,List.of()))))).andExpect(status().isConflict());
        assertEquals(4,countOrders()); assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM order_item",Integer.class));
        assertEquals(before,stock(1));
    }
    @Test void pendingCancellationRestoresOriginalSnapshotOnceEvenAfterRecipeChanges() throws Exception {
        double before=stock(1);
        var result=create(request(item(1,2,List.of(1L))));
        long id=result.get("order_id").asLong(),line=result.get("items").get(0).get("item_id").asLong();
        jdbc.update("UPDATE product SET recipe_json='{}' WHERE product_id=1");
        jdbc.update("UPDATE topping SET deduct_qty=10 WHERE topping_id=1");
        cancel(id,line,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        assertEquals(before,stock(1));
        double pearl=stock(6);
        cancel(id,line,null,null).andExpect(status().isOk());
        assertEquals(pearl,stock(6)); assertEquals(before,stock(1));
    }
    @Test void makingCancellationNeedsNoPinRecordsActorAndNeverRestoresStock() throws Exception {
        var result=create(request(item(1,1,List.of())));
        long id=result.get("order_id").asLong(),line=result.get("items").get(0).get("item_id").asLong();
        state(id,line,ItemStatus.MAKING).andExpect(status().isOk());
        double remaining=stock(1);
        cancel(id,line,null,null).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].status").value("WASTED")).andExpect(jsonPath("$.items[0].approved_by").value(10));
        cancel(id,line,"test_manager","000000").andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].approved_by").value(10));
        assertEquals(remaining,stock(1));
    }
    @Test void disabledEmployeeCannotCancelEvenWithManagerFields() throws Exception {
        var result=create(request(item(1,1,List.of())));
        long id=result.get("order_id").asLong(),line=result.get("items").get(0).get("item_id").asLong();
        state(id,line,ItemStatus.MAKING);
        jdbc.update("UPDATE staff_account SET active=FALSE WHERE staff_id=10");
        cancel(id,line,"test_manager","654321").andExpect(status().isUnauthorized());
    }
    @Test void wholeOrderCancellationWithoutPinRestoresOnlyPendingLines() throws Exception {
        var result=create(request(item(1,1,List.of()),item(3,1,List.of())));
        long id=result.get("order_id").asLong(),line=result.get("items").get(1).get("item_id").asLong();
        state(id,line,ItemStatus.MAKING);
        double remaining=stock(1);
        mvc.perform(post("/api/orders/"+id+"/cancel").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content("{\"reason\":\"取消\",\"refundAmount\":0}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[1].status").value("WASTED")).andExpect(jsonPath("$.items[1].approved_by").value(10));
        assertEquals(remaining+1,stock(1));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM order_item WHERE item_id=?",String.class,result.get("items").get(0).get("item_id").asLong()));
    }
    @Test void statesCannotSkipForwardOrCancelPickedUp() throws Exception {
        var result=create(request(item(1,1,List.of())));
        long id=result.get("order_id").asLong(),line=result.get("items").get(0).get("item_id").asLong();
        state(id,line,ItemStatus.DONE).andExpect(status().isConflict());
        for(var next:List.of(ItemStatus.MAKING,ItemStatus.DONE,ItemStatus.PICKED_UP)) state(id,line,next).andExpect(status().isOk());
        cancel(id,line,"test_manager","654321").andExpect(status().isConflict());
    }
    @Test void cashChangeIncludesReturnedRefundCashWithoutChangingOriginalTender() throws Exception {
        var base=request(item(1,1,List.of(1L)));
        var cash=new Create(base.requestId(),base.source(),Payment.CASH,null,new BigDecimal("100"),null,null,null,null,base.items());
        var result=create(cash);
        assertEquals(35,result.get("total").asInt()); assertEquals(65,result.get("change_cash").asInt());
        cancel(result.get("order_id").asLong(),result.get("items").get(0).get("item_id").asLong(),null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.received_cash").value(100)).andExpect(jsonPath("$.change_cash").value(100));
    }
    @Test void insufficientCashAndClientPriceAreRejected() throws Exception {
        var base=request(item(1,1,List.of()));
        var cash=new Create(base.requestId(),base.source(),Payment.CASH,null,new BigDecimal("1"),null,null,null,null,base.items());
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(cash))).andExpect(status().isBadRequest());
        var tree=json.valueToTree(base); ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("total",1);
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(tree))).andExpect(status().isBadRequest());
    }
    @Test void concurrentCheckoutCannotOversell() throws Exception {
        jdbc.update("UPDATE inventory SET book_qty=1 WHERE inventory_id=1");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            Callable<Integer> work=()->{ready.countDown();start.await();try {
                orders.create(request(item(1,1,List.of())),"test_staff"); return 200;
            } catch(ApiException ex) { return ex.status.value(); }};
            var a=pool.submit(work);var b=pool.submit(work);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var results=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));Collections.sort(results);
            assertEquals(List.of(200,409),results);assertEquals(0,stock(1));assertEquals(5,countOrders());
        }
    }
    @Test void concurrentIdenticalRetriesCreateOneOrder() throws Exception {
        var request=request(item(1,1,List.of()));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->orders.create(request,"test_staff"));
            var b=pool.submit(()->orders.create(request,"test_staff"));
            assertEquals(a.get(15,TimeUnit.SECONDS).get("order_id"),b.get(15,TimeUnit.SECONDS).get("order_id"));
            assertEquals(5,countOrders());assertEquals(97,stock(1));
        }
    }
    @Test void photosValidateImageContentAndOnlyAllowOneOnWastedLine() throws Exception {
        var result=create(request(item(1,1,List.of())));
        long id=result.get("order_id").asLong(),line=result.get("items").get(0).get("item_id").asLong();
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var image=new MockMultipartFile("file","../../unsafe.png","image/png",bytes.toByteArray());
        String url="/api/orders/"+id+"/items/"+line+"/photo";
        mvc.perform(multipart(url).file(image).with(httpBasic("test_staff",PASSWORD))).andExpect(status().isConflict());
        state(id,line,ItemStatus.MAKING);cancel(id,line,"test_manager","654321");
        mvc.perform(multipart(url).file(new MockMultipartFile("file","fake.png","image/png","not an image".getBytes()))
            .with(httpBasic("test_staff",PASSWORD))).andExpect(status().isBadRequest());
        mvc.perform(multipart(url).file(image).with(httpBasic("test_staff",PASSWORD))).andExpect(status().isOk());
        mvc.perform(multipart(url).file(image).with(httpBasic("test_staff",PASSWORD))).andExpect(status().isConflict());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(httpBasic("test_staff",PASSWORD))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        try(var files=Files.list(Path.of("build/test-photos"))) { assertEquals(1,files.count()); }
    }

    @Test void fractionalPricesCashAndCancellationStayExact() throws Exception {
        jdbc.update("UPDATE product SET base_price=25.15,large_extra=5.05 WHERE product_id=1");
        jdbc.update("UPDATE topping SET price=10.10 WHERE topping_id=1");
        Item line=new Item(1L,Size.L,Temperature.COLD,Sugar.HALF,Ice.LESS,3,List.of(1L),null);
        var request=new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.CASH,null,
            new BigDecimal("200.25"),new BigDecimal("1234.50"),180,null,null,List.of(line));
        var result=create(request);
        assertEquals(0,new BigDecimal("120.90").compareTo(result.get("total").decimalValue()));
        assertEquals(0,new BigDecimal("40.30").compareTo(result.get("items").get(0).get("unit_price").decimalValue()));
        assertEquals(0,new BigDecimal("79.35").compareTo(result.get("change_cash").decimalValue()));
        assertEquals(0,new BigDecimal("1234.50").compareTo(result.get("distance").decimalValue()));
        assertEquals(180,result.get("duration").asInt());
        assertTrue(result.get("items").get(0).get("toppings").get(0).get("order_topping_id").asLong()>0);
        String response=cancel(result.get("order_id").asLong(),result.get("items").get(0).get("item_id").asLong(),null,null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0,new BigDecimal("200.25").compareTo(json.readTree(response).get("change_cash").decimalValue()));
    }

    @Test void rejectsExcessiveScaleOrOutOfRangeAmountsBeforeWriting() throws Exception {
        for(String amount:List.of("100.001","100000000.00","-1")) {
            var request=new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.CASH,null,
                new BigDecimal(amount),null,null,null,null,List.of(item(1,1,List.of())));
            mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
                .content(json.writeValueAsBytes(request))).andExpect(status().isBadRequest());
        }
        assertEquals(4,countOrders());
    }

    @Test void totalCannotOverflowDatabaseDecimalRange() throws Exception {
        jdbc.update("UPDATE product SET base_price=99999999.99 WHERE product_id=1");
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(request(item(1,2,List.of()))))).andExpect(status().isBadRequest());
        assertEquals(4,countOrders());assertEquals(98,stock(1));
    }

    @Test void oldDistanceAliasesAndDecimalRepresentationsShareRequestIdentity() throws Exception {
        UUID requestId=UUID.randomUUID();
        String oldBody="""
            {"requestId":"%s","source":"TAKE_OUT","payment":"CASH","receivedCash":100,
             "distanceM":1200,"durationSeconds":60,
             "items":[{"productId":1,"size":"M","temperature":"COLD","sugar":"HALF","ice":"LESS","qty":1,"toppingIds":[]}]}
            """.formatted(requestId);
        JsonNode first=json.readTree(mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content(oldBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var normalized=new Create(requestId,Source.TAKE_OUT,Payment.CASH,null,new BigDecimal("100.00"),
            new BigDecimal("1200.00"),60,null,null,List.of(item(1,1,List.of())));
        var second=create(normalized);
        assertEquals(first.get("order_id"),second.get("order_id"));assertEquals(5,countOrders());

        // Pre-migration DTO serialized integers, null fields and these names in this order.
        String legacy="""
            {"requestId":"%s","source":"TAKE_OUT","payment":"CASH","memberPhone":null,"receivedCash":100,"distanceM":1200,"durationSeconds":60,"addressNote":null,"note":null,"items":[{"productId":1,"size":"M","temperature":"COLD","sugar":"HALF","ice":"LESS","qty":1,"toppingIds":[],"note":null}]}
            """.formatted(requestId).strip();
        String expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected,jdbc.queryForObject("SELECT request_hash FROM pos_order WHERE order_id=?",String.class,first.get("order_id").asLong()));
    }

    @Test void independentToppingIdKeepsUniqueItemToppingPair() throws Exception {
        Long id=jdbc.queryForObject("SELECT order_topping_id FROM order_item_topping WHERE item_id=1 AND topping_id=1",Long.class);
        assertNotNull(id);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
            jdbc.update("INSERT INTO order_item_topping(item_id,topping_id,topping_name,price_at_order) VALUES (1,1,'duplicate',10.25)"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM order_item_topping",Integer.class));
    }

    private Create unpaid(Item... lines) {
        return new Create(UUID.randomUUID(),Source.TAKE_OUT,null,null,null,null,null,null,null,List.of(lines),true);
    }
    private JsonNode explicitCancel(JsonNode order,BigDecimal refund,String manager,String pin,int expected) throws Exception {
        String body=mvc.perform(post("/api/orders/"+order.get("order_id").asLong()+"/cancel")
            .with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(new Cancel("顧客未取餐",manager,pin,refund))))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    @Test void unpaidPendingCancellationRestoresStockAndNeverRefunds() throws Exception {
        var request=unpaid(item(1,2,List.of(1L)));
        var order=create(request); long id=order.get("order_id").asLong();
        assertEquals("UNPAID",order.get("payment_status").asText());
        assertTrue(order.get("payment").isNull()); assertEquals(0,order.get("paid_amount").asInt());
        assertEquals(new BigDecimal("13.6"),order.get("items").get(0).get("material_cost").decimalValue().stripTrailingZeros());
        explicitCancel(order,BigDecimal.ONE,null,null,400);
        assertEquals(96,stock(1));
        var cancelled=explicitCancel(order,BigDecimal.ZERO,null,null,200);
        assertEquals(0,cancelled.get("refunded_amount").asInt()); assertEquals(98,stock(1));
        explicitCancel(order,BigDecimal.ZERO,null,null,200); assertEquals(98,stock(1));
        assertEquals(id,create(request).get("order_id").asLong());
        mvc.perform(post("/api/orders/"+id+"/collect").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content("{\"payment\":\"LINE_PAY\"}" )).andExpect(status().isConflict());
    }
    @Test void unpaidMadeWastageKeepsCostSnapshotWithoutRefundOrStockRestore() throws Exception {
        var order=create(unpaid(item(4,2,List.of(2L))));
        long id=order.get("order_id").asLong(),line=order.get("items").get(0).get("item_id").asLong();
        state(id,line,ItemStatus.MAKING).andExpect(status().isOk());
        jdbc.update("UPDATE inventory SET unit_cost=99 WHERE inventory_id=1");
        var cancelled=explicitCancel(order,BigDecimal.ZERO,null,null,200);
        var item=cancelled.get("items").get(0);
        assertEquals("WASTED",item.get("status").asText());
        assertEquals(0,new BigDecimal("30.60").compareTo(item.get("material_cost").decimalValue()));
        assertEquals(0,cancelled.get("paid_amount").asInt()); assertEquals(0,cancelled.get("refunded_amount").asInt());
        assertEquals(96,stock(1));
    }
    @Test void paidCancellationCanKeepReceiptsOrRecordPartialRefundExactlyOnce() throws Exception {
        var order=create(new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.CASH,null,new BigDecimal("100"),null,null,null,null,List.of(item(1,2,List.of()))));
        explicitCancel(order,new BigDecimal("50.01"),null,null,400);
        var result=explicitCancel(order,new BigDecimal("10.25"),null,null,200);
        assertEquals(0,new BigDecimal("50").compareTo(result.get("paid_amount").decimalValue()));
        assertEquals(0,new BigDecimal("10.25").compareTo(result.get("refunded_amount").decimalValue()));
        assertEquals(0,new BigDecimal("60.25").compareTo(result.get("change_cash").decimalValue()));
        assertEquals(0,new BigDecimal("10.25").compareTo(result.get("items").get(0).get("refund_amount").decimalValue()));
        explicitCancel(order,new BigDecimal("10.25"),null,null,200);
        assertEquals(new BigDecimal("10.25"),jdbc.queryForObject("SELECT refunded_amount FROM pos_order WHERE order_id=?",BigDecimal.class,order.get("order_id").asLong()));
        var second=create(request(item(1,1,List.of())));
        var noRefund=explicitCancel(second,BigDecimal.ZERO,null,null,200);
        assertEquals(25,noRefund.get("paid_amount").asInt());assertEquals(0,noRefund.get("refunded_amount").asInt());
    }
    @Test void collectingUnpaidOrderRequiresFullAmountAndCannotCollectTwice() throws Exception {
        var order=create(unpaid(item(1,1,List.of())));
        long id=order.get("order_id").asLong(),line=order.get("items").get(0).get("item_id").asLong();
        state(id,line,ItemStatus.MAKING);state(id,line,ItemStatus.DONE);
        state(id,line,ItemStatus.PICKED_UP).andExpect(status().isConflict());
        String path="/api/orders/"+id+"/collect";
        for(String body:List.of("{\"payment\":\"CASH\",\"receivedCash\":20}","{\"payment\":\"LINE_PAY\",\"receivedCash\":100}","{\"payment\":\"UBER_EATS\"}"))
            mvc.perform(post(path).with(httpBasic("test_staff",PASSWORD)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        String body="{\"payment\":\"CASH\",\"receivedCash\":100}";
        for(int retry=0;retry<2;retry++) mvc.perform(post(path).with(httpBasic("test_staff",PASSWORD)).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.payment_status").value("PAID"))
            .andExpect(jsonPath("$.paid_amount").value(25)).andExpect(jsonPath("$.change_cash").value(75));
        mvc.perform(post(path).with(httpBasic("test_staff",PASSWORD)).contentType("application/json").content("{\"payment\":\"LINE_PAY\"}")).andExpect(status().isConflict());
        state(id,line,ItemStatus.PICKED_UP).andExpect(status().isOk()); assertEquals(97,stock(1));
    }
    @Test void unknownIngredientCostIsNeverReportedAsZero() throws Exception {
        jdbc.update("UPDATE inventory SET unit_cost=NULL WHERE inventory_id=6");
        var order=create(unpaid(item(1,1,List.of(1L))));
        assertTrue(order.get("items").get(0).get("material_cost").isNull());
    }
    @Test void unpaidCannotClaimPaymentAndPaidOrdersRequireMethod() throws Exception {
        for(var bad:List.of(new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.LINE_PAY,null,null,null,null,null,null,List.of(item(1,1,List.of())),true),
            new Create(UUID.randomUUID(),Source.TAKE_OUT,null,null,null,null,null,null,null,List.of(item(1,1,List.of())),false)))
            mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json").content(json.writeValueAsBytes(bad))).andExpect(status().isBadRequest());
        assertEquals(4,countOrders());
    }
    private org.springframework.test.web.servlet.ResultActions bulk(long id,ItemStatus next) throws Exception {
        return mvc.perform(post("/api/orders/"+id+"/status").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content(json.writeValueAsBytes(new ChangeStatus(next))));
    }
    @Test void bulkProductionAdvancesOnlyEligibleLinesAndIsRetrySafe() throws Exception {
        var order=create(request(item(1,2,List.of()),item(2,1,List.of()),item(3,1,List.of())));
        long id=order.get("order_id").asLong(),first=order.get("items").get(0).get("item_id").asLong();
        long last=order.get("items").get(2).get("item_id").asLong();
        state(id,first,ItemStatus.MAKING);state(id,first,ItemStatus.DONE);
        cancel(id,last,null,null).andExpect(status().isOk());
        double before=stock(1);
        for(int retry=0;retry<2;retry++) bulk(id,ItemStatus.MAKING).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].status").value("DONE"))
            .andExpect(jsonPath("$.items[1].status").value("MAKING"))
            .andExpect(jsonPath("$.items[2].status").value("CANCELLED"));
        bulk(id,ItemStatus.PICKED_UP).andExpect(status().isConflict());
        assertEquals("DONE",jdbc.queryForObject("SELECT status FROM order_item WHERE item_id=?",String.class,first));
        bulk(id,ItemStatus.DONE).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        for(int retry=0;retry<2;retry++) bulk(id,ItemStatus.PICKED_UP).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PICKED_UP"))
            .andExpect(jsonPath("$.items[2].status").value("CANCELLED"));
        assertEquals(before,stock(1));
    }
    @Test void bulkPickupRejectsPendingOrUnpaidAndAcceptsPartiallyCollectedOrder() throws Exception {
        var order=create(unpaid(item(1,1,List.of()),item(2,1,List.of())));
        long id=order.get("order_id").asLong();
        bulk(id,ItemStatus.PICKED_UP).andExpect(status().isConflict());
        bulk(id,ItemStatus.MAKING).andExpect(status().isOk());bulk(id,ItemStatus.DONE).andExpect(status().isOk());
        bulk(id,ItemStatus.PICKED_UP).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/"+id+"/collect").with(httpBasic("test_staff",PASSWORD))
            .contentType("application/json").content("{\"payment\":\"LINE_PAY\"}")).andExpect(status().isOk());
        state(id,order.get("items").get(0).get("item_id").asLong(),ItemStatus.PICKED_UP).andExpect(status().isOk());
        bulk(id,ItemStatus.PICKED_UP).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
    }
    @Test void bulkCannotCancelBypassAuthenticationOrModifyClosedShift() throws Exception {
        mvc.perform(post("/api/orders/1/status").contentType("application/json").content("{\"status\":\"MAKING\"}"))
            .andExpect(status().isUnauthorized());
        bulk(1,ItemStatus.CANCELLED).andExpect(status().isBadRequest());
        jdbc.update("UPDATE daily_shift SET status='CLOSED',closed_by=1,closed_at=CURRENT_TIMESTAMP,revenue_by_payment='{}',cups=0,expected_cash=2000,counted_cash=2000 WHERE business_date='2026-10-28'");
        bulk(1,ItemStatus.MAKING).andExpect(status().isConflict());
    }
    @Test void uberEatsIsAnOrderSourceNotANewPaymentMethod() throws Exception {
        var base=request(item(1,1,List.of()));
        var invalid=new Create(base.requestId(),base.source(),Payment.UBER_EATS,null,null,null,null,null,null,base.items());
        mvc.perform(post("/api/orders").with(httpBasic("test_staff",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        assertEquals(4,countOrders());
        var valid=new Create(UUID.randomUUID(),Source.UBER_EATS,Payment.LINE_PAY,null,null,null,null,null,null,base.items());
        assertEquals("UBER_EATS",create(valid).get("source").asText());
    }
}
