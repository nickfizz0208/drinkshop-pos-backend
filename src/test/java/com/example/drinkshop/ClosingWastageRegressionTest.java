package com.example.drinkshop;

import com.example.drinkshop.dto.CloseShiftRequest;
import com.example.drinkshop.dto.OpenShiftRequest;
import com.example.drinkshop.dto.RawMaterialWastageRequest;
import com.example.drinkshop.dto.StockQuantityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 新增模組（庫存、配料、報廢、日結班次）整合測試
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "pos.raw-wastage.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:pos_new_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-new"
})
@AutoConfigureMockMvc
public class ClosingWastageRegressionTest {

    private static final String TEST_USER = "test_staff";
    private static final String TEST_PASS = "staff123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClock {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        java.time.Clock fixedClock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-10-28T03:00:00Z"),java.time.ZoneId.of("Asia/Taipei"));
        }
    }

    @Autowired com.example.drinkshop.service.OrderService orders;
    @Autowired com.example.drinkshop.service.DailyShiftService shifts;
    @Autowired com.example.drinkshop.service.WastageService wastes;
    @Autowired com.example.drinkshop.service.RuleService json;
    @Autowired com.example.drinkshop.service.InventoryService inventoryService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach
    void resetDatabase() {
        try (var connection=dataSource.getConnection()) {
            org.junit.jupiter.api.Assertions.assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        } catch (java.sql.SQLException ex) { throw new RuntimeException(ex); }
        jdbc.execute("DROP ALL OBJECTS");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema-h2.sql"),
                new ClassPathResource("data-h2.sql")
        );
        populator.execute(dataSource);
        jdbc.update("DELETE FROM order_item_topping");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM pos_order");
        jdbc.update("UPDATE daily_shift SET petty_cash=1000 WHERE business_date='2026-10-28'");

        // 新增具備合法密碼 hash 的測試員工
        jdbc.update("INSERT INTO staff_account(staff_id, username, password_hash, name, level, active) " +
                "VALUES (10, ?, ?, '測試店員', 'STAFF', TRUE)",
                TEST_USER, passwordEncoder.encode(TEST_PASS));
    }


    private final LocalDate day=LocalDate.of(2026,10,28);
    private com.fasterxml.jackson.databind.JsonNode create(com.example.drinkshop.dto.OrderRequests.Payment payment, boolean unpaid) throws Exception {
        var item=new com.example.drinkshop.dto.OrderRequests.Item(1L, com.example.drinkshop.dto.OrderRequests.Size.M,
            com.example.drinkshop.dto.OrderRequests.Temperature.COLD,com.example.drinkshop.dto.OrderRequests.Sugar.HALF,
            com.example.drinkshop.dto.OrderRequests.Ice.LESS,1,java.util.List.of(),null);
        var request=new com.example.drinkshop.dto.OrderRequests.Create(java.util.UUID.randomUUID(),
            com.example.drinkshop.dto.OrderRequests.Source.TAKE_OUT,payment,null,
            payment==com.example.drinkshop.dto.OrderRequests.Payment.CASH?new BigDecimal("100"):null,
            null,null,null,null,java.util.List.of(item),unpaid);
        return mapper.valueToTree(orders.create(request,TEST_USER));
    }
    private long itemId(com.fasterxml.jackson.databind.JsonNode order) { return order.get("items").get(0).get("item_id").asLong(); }
    private org.springframework.test.web.servlet.ResultActions waste(long id, String body) throws Exception {
        return mockMvc.perform(post("/api/wastages/order-item/"+id).with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private void raw(String body) throws Exception {
        mockMvc.perform(post("/api/wastages/raw-material").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
    @Test void cashExcludesNonCashUnpaidAndAccountsForActualPartialRefund() throws Exception {
        var cash=create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false);
        create(com.example.drinkshop.dto.OrderRequests.Payment.LINE_PAY,false);
        create(null,true);
        orders.cancel(cash.get("order_id").asLong(),itemId(cash),
            new com.example.drinkshop.dto.OrderRequests.Cancel("測試退款",null,null,new BigDecimal("10")),TEST_USER);
        var summary=shifts.closeShift(day,10L,new BigDecimal("1015"),null);
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("1015").compareTo(summary.expectedCash()));
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("50").compareTo(summary.totalRevenue()));
        var receipts=json.parse(jdbc.queryForObject("SELECT revenue_by_payment FROM daily_shift WHERE business_date=?",String.class,day));
        org.junit.jupiter.api.Assertions.assertEquals(15,receipts.path("CASH").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(25,receipts.path("LINE_PAY").asInt());
    }
    @Test void rawWastageKeepsReasonCostSnapshotAndLeavesStockCountUntouched() throws Exception {
        jdbc.update("UPDATE inventory SET unit_cost=2,reported_qty=99,reported_by=1,reported_at=CURRENT_TIMESTAMP WHERE inventory_id=1");
        BigDecimal before=jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class);
        raw("{\"inventoryId\":1,\"wastageQty\":3,\"reason\":\"測試打翻\",\"staffId\":1}");
        org.junit.jupiter.api.Assertions.assertEquals(0,before.subtract(new BigDecimal("3")).compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class)));
        org.junit.jupiter.api.Assertions.assertEquals(99,jdbc.queryForObject("SELECT reported_qty FROM inventory WHERE inventory_id=1",BigDecimal.class).intValue());
        jdbc.update("UPDATE inventory SET unit_cost=9 WHERE inventory_id=1");
        var summary=wastes.getDailyWastageSummary(day);
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("6").compareTo(summary.totalWastageCost()));
        org.junit.jupiter.api.Assertions.assertEquals("測試打翻",summary.rawMaterialWastages().get(0).reason());
        org.junit.jupiter.api.Assertions.assertEquals(10L,summary.rawMaterialWastages().get(0).reportedBy());
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("1000").compareTo(shifts.closeShift(day,10L,new BigDecimal("1000"),null).expectedCash()));
    }
    @Test void madeItemWasteUpdatesParentWithoutSecondDeductionOrAutomaticRefund() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false); long id=itemId(order);
        orders.changeStatus(order.get("order_id").asLong(),id,com.example.drinkshop.dto.OrderRequests.ItemStatus.MAKING,TEST_USER);
        BigDecimal before=jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class);
        waste(id,"{\"reason\":\"做錯\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WASTED"));
        waste(id,"{\"reason\":\"重試\"}").andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT total FROM pos_order WHERE order_id=?",BigDecimal.class,order.get("order_id").asLong()).signum());
        org.junit.jupiter.api.Assertions.assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM pos_order WHERE order_id=?",String.class,order.get("order_id").asLong()));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT refunded_amount FROM pos_order WHERE order_id=?",BigDecimal.class,order.get("order_id").asLong()).signum());
        org.junit.jupiter.api.Assertions.assertEquals(0,before.compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class)));
    }
    @Test void explicitRefundIsAppliedOnceAndClosedShiftRejectsFurtherWaste() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false); long id=itemId(order);
        orders.changeStatus(order.get("order_id").asLong(),id,com.example.drinkshop.dto.OrderRequests.ItemStatus.MAKING,TEST_USER);
        waste(id,"{\"reason\":\"退款報廢\",\"refundAmount\":10}").andExpect(status().isOk());
        waste(id,"{\"reason\":\"重試\",\"refundAmount\":10}").andExpect(status().isOk());
        var close=shifts.closeShift(day,10L,new BigDecimal("1015"),null);
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("1015").compareTo(close.expectedCash()));
        waste(id,"{\"reason\":\"關班後不可改\"}").andExpect(status().isConflict());
    }
    @Test void pendingAndPickedUpItemsCannotBypassOriginalCancellationRules() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.LINE_PAY,false); long id=itemId(order);
        waste(id,"{\"reason\":\"未做\"}").andExpect(status().isConflict());
        for(var status:java.util.List.of(com.example.drinkshop.dto.OrderRequests.ItemStatus.MAKING,
            com.example.drinkshop.dto.OrderRequests.ItemStatus.DONE,com.example.drinkshop.dto.OrderRequests.ItemStatus.PICKED_UP))
            orders.changeStatus(order.get("order_id").asLong(),id,status,TEST_USER);
        waste(id,"{\"reason\":\"已取餐\"}").andExpect(status().isConflict());
    }
    @Test void unknownCostIsNotSubstitutedWithSellingPrice() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.LINE_PAY,false); long id=itemId(order);
        orders.changeStatus(order.get("order_id").asLong(),id,com.example.drinkshop.dto.OrderRequests.ItemStatus.MAKING,TEST_USER);
        waste(id,"{\"reason\":\"做錯\"}").andExpect(status().isOk());
        jdbc.update("UPDATE order_item SET material_cost=NULL WHERE item_id=?",id);
        var summary=wastes.getDailyWastageSummary(day);
        org.junit.jupiter.api.Assertions.assertFalse(summary.costComplete());
        org.junit.jupiter.api.Assertions.assertNull(summary.totalWastageCost());
    }
    @Test void actorCannotBeOverriddenInStockReportOpeningOrClosing() throws Exception {
        mockMvc.perform(post("/api/inventories/1/report").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50,\"staffId\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reportedBy").value(10));
        mockMvc.perform(post("/api/daily-shifts/open").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content("{\"businessDate\":\"2027-05-20\",\"pettyCash\":0,\"staffId\":1}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.openedBy").value(10));
        mockMvc.perform(post("/api/daily-shifts/close").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content("{\"businessDate\":\"2027-05-20\",\"countedCash\":0,\"staffId\":1}"))
            .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(10L,jdbc.queryForObject("SELECT closed_by FROM daily_shift WHERE business_date='2027-05-20'",Long.class));
    }
    @Test void missingAuditTableRollsBackStockDeduction() {
        BigDecimal before=jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class);
        jdbc.execute("DROP TABLE raw_material_wastage");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
            () -> wastes.recordRawMaterialWastage(1L,BigDecimal.ONE,"測試",10L));
        org.junit.jupiter.api.Assertions.assertEquals(0,before.compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class)));
    }
    @Test void cashDifferenceNeedsReasonAndFailedClosingDoesNotCloseShift() throws Exception {
        mockMvc.perform(post("/api/daily-shifts/close").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content("{\"businessDate\":\"2026-10-28\",\"countedCash\":999}"))
            .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals("OPEN",shifts.getShift(day).getStatus());
    }
    @Test void rawWasteAfterClosingIsRejectedAndDoesNotDeduct() throws Exception {
        shifts.closeShift(day,10L,new BigDecimal("1000"),null);
        BigDecimal before=jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class);
        mockMvc.perform(post("/api/wastages/raw-material").with(httpBasic(TEST_USER,TEST_PASS))
            .contentType(MediaType.APPLICATION_JSON).content("{\"inventoryId\":1,\"wastageQty\":1,\"reason\":\"測試\"}"))
            .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(0,before.compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class)));
    }

    @Test void closingWaitsForCheckoutUsingTheSameShiftLock() throws Exception {
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var entered=new java.util.concurrent.CountDownLatch(1);
            var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            var closing=tx.execute(status -> {
                jdbc.queryForMap("SELECT * FROM daily_shift WHERE business_date=? FOR UPDATE",day);
                var future=executor.submit(() -> {
                    entered.countDown();
                    return shifts.closeShift(day,10L,new BigDecimal("1025"),null);
                });
                try {
                    org.junit.jupiter.api.Assertions.assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    org.junit.jupiter.api.Assertions.assertFalse(future.isDone());
                    create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false);
                } catch (Exception ex) { throw new RuntimeException(ex); }
                return future;
            });
            org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("1025").compareTo(closing.get(10,java.util.concurrent.TimeUnit.SECONDS).expectedCash()));
        } finally { executor.shutdownNow(); }
    }

    @Test void featureDisabledDoesNotDeductOrWriteAnEvent() {
        var inventory=org.mockito.Mockito.mock(com.example.drinkshop.service.InventoryService.class);
        var service=new com.example.drinkshop.service.WastageService(inventory,null,null,null,null,
            java.time.Clock.systemUTC(),false);
        var error=org.junit.jupiter.api.Assertions.assertThrows(com.example.drinkshop.exception.ApiException.class,
            () -> service.recordRawMaterialWastage(1L,BigDecimal.ONE,"測試",10L));
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,error.status);
        org.mockito.Mockito.verifyNoInteractions(inventory);
    }

    @Test void creditCardAndFullCashRefundDoNotLeaveExtraCash() throws Exception {
        var cash=create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false);
        create(com.example.drinkshop.dto.OrderRequests.Payment.CREDIT_CARD,false);
        orders.cancel(cash.get("order_id").asLong(),itemId(cash),
            new com.example.drinkshop.dto.OrderRequests.Cancel("全額退款",null,null,new BigDecimal("25")),TEST_USER);
        var result=shifts.closeShift(day,10L,new BigDecimal("1000"),null);
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("1000").compareTo(result.expectedCash()));
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("25").compareTo(result.totalRevenue()));
    }

    @Test void businessDateIsUsedEvenWhenItemTimestampFallsOnAnotherDay() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.LINE_PAY,false); long id=itemId(order);
        jdbc.update("UPDATE order_item SET created_at='2020-01-01 12:00:00' WHERE item_id=?",id);
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("25").compareTo(shifts.closeShift(day,10L,new BigDecimal("1000"),null).totalRevenue()));
        org.junit.jupiter.api.Assertions.assertEquals(1,shifts.getShift(day).getCups());
    }

    @Test void excessiveRefundRollsBackStatusAndMoney() throws Exception {
        var order=create(com.example.drinkshop.dto.OrderRequests.Payment.CASH,false); long id=itemId(order);
        orders.changeStatus(order.get("order_id").asLong(),id,com.example.drinkshop.dto.OrderRequests.ItemStatus.MAKING,TEST_USER);
        waste(id,"{\"reason\":\"超額退款\",\"refundAmount\":26}").andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals("MAKING",jdbc.queryForObject("SELECT status FROM order_item WHERE item_id=?",String.class,id));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT refunded_amount FROM pos_order WHERE order_id=?",BigDecimal.class,order.get("order_id").asLong()).signum());
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void concurrentRawWastagesOnlyDeductAvailableStock() throws Exception {
        jdbc.update("UPDATE inventory SET book_qty=3,unit_cost=2 WHERE inventory_id=1");
        runTwoDeductions(() -> wastes.recordRawMaterialWastage(1L,new BigDecimal("2"),"同時報廢測試",10L));
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM raw_material_wastage",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,new BigDecimal("4").compareTo(wastes.getDailyWastageSummary(day).totalWastageCost()));
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void concurrentInventoryDeductionsDoNotOverwriteEachOther() throws Exception {
        jdbc.update("UPDATE inventory SET book_qty=3 WHERE inventory_id=1");
        runTwoDeductions(() -> inventoryService.deductStock(1L,new BigDecimal("2")));
    }

    private void runTwoDeductions(java.lang.Runnable operation) throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++) jobs.add(executor.submit(() -> {
                ready.countDown(); start.await();
                try { operation.run(); return true; }
                catch(com.example.drinkshop.exception.ApiException ex) {
                    org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.HttpStatus.CONFLICT,ex.status);
                    return false;
                }
            }));
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            int success=0; for(var job:jobs) if(job.get(10,java.util.concurrent.TimeUnit.SECONDS)) success++;
            org.junit.jupiter.api.Assertions.assertEquals(1,success);
            org.junit.jupiter.api.Assertions.assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1",BigDecimal.class)));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
