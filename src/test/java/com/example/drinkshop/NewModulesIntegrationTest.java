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
                "VALUES (10, ?, ?, '測試店員', 'STAFF', TRUE)",
                TEST_USER, passwordEncoder.encode(TEST_PASS));
    }

    @Test
    void testInventoryFlow() throws Exception {
        // 1. 查詢所有原料
        mockMvc.perform(get("/api/inventories")
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 2. 依 ID 查特定原料 (ID: 1)
        mockMvc.perform(get("/api/inventories/1")
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());

        // 3. 校正庫存數量 (ID: 1, 數量: 500.00)
        StockQuantityRequest updateReq = new StockQuantityRequest(new BigDecimal("500.00"));
        mockMvc.perform(put("/api/inventories/1/stock")
                        .with(httpBasic(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookQty").value(500.0));

        // 4. 手動扣除庫存 (ID: 1, 扣除: 50.00)
        StockQuantityRequest deductReq = new StockQuantityRequest(new BigDecimal("50.00"));
        mockMvc.perform(post("/api/inventories/1/deduct")
                        .with(httpBasic(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(deductReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookQty").value(450.0));

        // 5. 查詢在售加料清單
        mockMvc.perform(get("/api/inventories/toppings")
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 6. 更新加料狀態為停售
        mockMvc.perform(put("/api/inventories/toppings/1/status")
                        .param("onSale", "false")
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onSale").value(false));
    }

    @Test
    void testDailyShiftFlow() throws Exception {
        LocalDate testDate = LocalDate.of(2027, 5, 20);

        // 1. 開班
        OpenShiftRequest openReq = new OpenShiftRequest(testDate, new BigDecimal("2000.00"), "[]", 10L);
        mockMvc.perform(post("/api/daily-shifts/open")
                        .with(httpBasic(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(openReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pettyCash").value(2000.0));

        // 2. 查詢班次
        mockMvc.perform(get("/api/daily-shifts/" + testDate)
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 3. 關班結算
        CloseShiftRequest closeReq = new CloseShiftRequest(testDate, new BigDecimal("2000.00"), "無短溢", 10L);
        mockMvc.perform(post("/api/daily-shifts/close")
                        .with(httpBasic(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(closeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.diffAmount").value(0.0));
    }

    @Test
    void testWastageFlow() throws Exception {
        // 1. 原料報廢 (原料 1 號，報廢 10.00)
        RawMaterialWastageRequest wasteReq = new RawMaterialWastageRequest(1L, new BigDecimal("10.00"), "過期變質", 10L);
        mockMvc.perform(post("/api/wastages/raw-material")
                        .with(httpBasic(TEST_USER, TEST_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(wasteReq)))
                .andExpect(status().isOk());

        // 2. 當日報廢統計
        mockMvc.perform(get("/api/wastages/daily-summary")
                        .with(httpBasic(TEST_USER, TEST_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWastedCount").exists())
                .andExpect(jsonPath("$.costComplete").value(false))
                .andExpect(jsonPath("$.rawMaterialWastages[0].reason").value("過期變質"));
    }
}
