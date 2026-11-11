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

import static com.example.drinkshop.TestLogin.login;
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
public class NewModulesIntegrationTest {

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

        // 新增具備合法密碼 hash 的測試員工
        jdbc.update("INSERT INTO staff_account(staff_id, username, password_hash, name, level, active) " +
                "VALUES (10, ?, ?, '門市 POS', 'POS', TRUE)",
                TEST_USER, passwordEncoder.encode(TEST_PASS));
        // 開班人員：在職員工本人（POS 帳號不能當開班人員）
        jdbc.update("INSERT INTO staff_account(staff_id, username, password_hash, name, level, active) " +
                "VALUES (11, 'opener', ?, '開班店員', 'STAFF', TRUE)", passwordEncoder.encode(TEST_PASS));
        jdbc.update("INSERT INTO staff_account(staff_id, username, password_hash, name, level, active) " +
                "VALUES (12, 'left_staff', ?, '離職店員', 'STAFF', FALSE)", passwordEncoder.encode(TEST_PASS));
    }

    @Test
    void testInventoryFlow() throws Exception {
        // 1. 查詢所有原料
        mockMvc.perform(get("/api/inventories")
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 2. 依 ID 查特定原料 (ID: 1)
        mockMvc.perform(get("/api/inventories/1")
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());

        // 3. 校正庫存數量 (ID: 1, 數量: 500.00)
        StockQuantityRequest updateReq = new StockQuantityRequest(new BigDecimal("500.00"));
        mockMvc.perform(put("/api/inventories/1/stock")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookQty").value(500.0));

        // 4. 手動扣除庫存 (ID: 1, 扣除: 50.00)
        StockQuantityRequest deductReq = new StockQuantityRequest(new BigDecimal("50.00"));
        mockMvc.perform(post("/api/inventories/1/deduct")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(deductReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookQty").value(450.0));

        // 5. 查詢在售加料清單
        mockMvc.perform(get("/api/inventories/toppings")
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 6. 更新加料狀態為停售
        mockMvc.perform(put("/api/inventories/toppings/1/status")
                        .param("onSale", "false")
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onSale").value(false));
    }

    @Test
    void testDailyShiftFlow() throws Exception {
        LocalDate testDate = LocalDate.of(2027, 5, 20);

        // 1. 開班
        OpenShiftRequest openReq = new OpenShiftRequest(testDate, new BigDecimal("2000.00"), "[]", 11L);
        mockMvc.perform(post("/api/daily-shifts/open")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(openReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pettyCash").value(2000.0))
                .andExpect(jsonPath("$.openedBy").value(11));

        // 重複開班：409 並給看得懂的訊息
        mockMvc.perform(post("/api/daily-shifts/open")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(openReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("今天已經開班，不可重複開班"));

        // 2. 查詢班次
        mockMvc.perform(get("/api/daily-shifts/" + testDate)
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 3. 關班結算
        // 關班人員不能是 POS 帳號自己
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate, new BigDecimal("2000.00"), "無短溢", 10L))))
                .andExpect(status().isBadRequest());
        CloseShiftRequest closeReq = new CloseShiftRequest(testDate, new BigDecimal("2000.00"), "無短溢", 11L);
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(closeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.diffAmount").value(0.0));

        // 重複關班、還沒開班就關班：409 並給看得懂的訊息
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(closeReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("今天已經關班，不可重複關班"));
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate.plusDays(9), new BigDecimal("0"), null, 11L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("今天還沒開班，不能關班"));
    }

    @Test
    void reopenCancelsClosingAndContinuesBusiness() throws Exception {
        LocalDate testDate = LocalDate.of(2027, 5, 23);
        String reopen = "{\"businessDate\":\"" + testDate + "\"}";
        // 還沒開班：不能重新開班
        mockMvc.perform(post("/api/daily-shifts/reopen").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("今天還沒開班，請直接開班"));
        mockMvc.perform(post("/api/daily-shifts/open").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenShiftRequest(testDate, new BigDecimal("1500"), "[]", 11L))))
                .andExpect(status().isCreated());
        // 營業中：不用重新開班
        mockMvc.perform(post("/api/daily-shifts/reopen").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("今天還在營業中，不用重新開班"));
        mockMvc.perform(post("/api/daily-shifts/close").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate, new BigDecimal("1500"), null, 11L))))
                .andExpect(status().isOk());
        // 關班後重新開班：回到 OPEN，關班結果清掉，開班人員與零用金不變
        mockMvc.perform(post("/api/daily-shifts/reopen").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedBy").isEmpty())
                .andExpect(jsonPath("$.openedBy").value(11))
                .andExpect(jsonPath("$.pettyCash").value(1500.0));
        // 可以再關一次
        mockMvc.perform(post("/api/daily-shifts/close").with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate, new BigDecimal("1500"), null, 11L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void cashShortageRequiresReason() throws Exception {
        LocalDate testDate = LocalDate.of(2027, 5, 22);
        mockMvc.perform(post("/api/daily-shifts/open")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenShiftRequest(testDate, new BigDecimal("2000"), "[]", 11L))))
                .andExpect(status().isCreated());
        // 短少 10 元沒寫原因：400，班次維持 OPEN
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate, new BigDecimal("1990"), "  ", 11L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("現金短少 $10（應有 $2000），請填寫差異原因"));
        org.junit.jupiter.api.Assertions.assertEquals("OPEN",
                jdbc.queryForObject("SELECT status FROM daily_shift WHERE business_date=?", String.class, testDate));
        // 寫了原因就可以關班
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CloseShiftRequest(testDate, new BigDecimal("1990"), "找錯零錢", 11L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffAmount").value(-10.0));
    }

    @Test
    void openShiftRequiresActivePersonAsOpener() throws Exception {
        LocalDate testDate = LocalDate.of(2027, 5, 21);
        // 沒選開班人員、選 POS 帳號自己、選離職員工、選不存在的人：都是 400，而且不會開班
        for (Long opener : new Long[]{null, 10L, 12L, 999L}) {
            mockMvc.perform(post("/api/daily-shifts/open")
                            .with(login(TEST_USER, TEST_PASS))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(new OpenShiftRequest(testDate, new BigDecimal("2000"), "[]", opener))))
                    .andExpect(status().isBadRequest());
        }
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM daily_shift WHERE business_date=?", Integer.class, testDate));
    }

    @Test
    void testWastageFlow() throws Exception {
        // 1. 原料報廢 (原料 1 號，報廢 10.00)
        RawMaterialWastageRequest wasteReq = new RawMaterialWastageRequest(1L, new BigDecimal("10.00"), "過期變質");
        mockMvc.perform(post("/api/wastages/raw-material")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(wasteReq)))
                .andExpect(status().isOk());
        // 通報人是目前登入的帳號（staff_id 10），不是前端指定的人；記在原料損耗紀錄，不改盤點欄位
        org.junit.jupiter.api.Assertions.assertEquals(10L,
                jdbc.queryForObject("SELECT reported_by FROM raw_material_wastage WHERE inventory_id=1", Long.class));
        // 前端多帶 staffId 想冒用別人：直接拒絕
        mockMvc.perform(post("/api/wastages/raw-material")
                        .with(login(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryId\":1,\"wastageQty\":1,\"reason\":\"打翻\",\"staffId\":11}"))
                .andExpect(status().isBadRequest());

        // 2. 當日報廢統計
        mockMvc.perform(get("/api/wastages/daily-summary")
                        .with(login(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWastedCount").exists())
                // 損耗金額只算原料報廢（飲品不計成本，021）：中杯紙杯 10 個 × 0.80 = 8
                .andExpect(jsonPath("$.costComplete").value(true))
                .andExpect(jsonPath("$.totalWastageCost").value(8.0))
                .andExpect(jsonPath("$.rawMaterialWastages[0].reason").value("過期變質"));
    }
}
