package com.example.drinkshop;

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
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static com.example.drinkshop.TestLogin.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 原料盤點：只記錄、不改帳面；只限個人帳號；重送不重複；同一批全部成功或全部不記錄。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "pos.inventory-count.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:pos_count_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-count"
})
@AutoConfigureMockMvc
class InventoryCountApiTest {
    private static final String PASSWORD = "pass1234";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"), new ClassPathResource("data-h2.sql"))
                .execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'c_staff',?,'盤點員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'c_other',?,'另一員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (12,'c_pos',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
        // 固定帳面數量與成本，讓差異好算
        jdbc.update("UPDATE inventory SET book_qty=100, unit_cost=2 WHERE inventory_id=1");
        jdbc.update("UPDATE inventory SET book_qty=100, unit_cost=NULL WHERE inventory_id=5");
    }

    private ResultActions submit(String user, String body) throws Exception {
        return mvc.perform(post("/api/inventory-counts").with(login(user, PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void countIsRecordedWithSnapshotButBookQtyIsUnchanged() throws Exception {
        String id = UUID.randomUUID().toString();
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"note\":\"月底盤點\",\"lines\":["
                + "{\"inventoryId\":1,\"countedQty\":95.5},{\"inventoryId\":5,\"countedQty\":103}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].inventoryId").value(1))
                .andExpect(jsonPath("$[0].bookQty").value(100.0))
                .andExpect(jsonPath("$[0].diffQty").value(-4.5))
                .andExpect(jsonPath("$[0].diffCost").value(-9.0))
                .andExpect(jsonPath("$[0].countedBy").value(10))
                .andExpect(jsonPath("$[0].countedByName").value("盤點員工"))
                .andExpect(jsonPath("$[0].note").value("月底盤點"))
                .andExpect(jsonPath("$[1].diffQty").value(3.0))
                .andExpect(jsonPath("$[1].diffCost").doesNotExist());
        // 帳面庫存不變；原料主檔的「最新盤點」欄位更新成這次
        assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1", BigDecimal.class)));
        assertEquals(0, new BigDecimal("95.5").compareTo(jdbc.queryForObject("SELECT reported_qty FROM inventory WHERE inventory_id=1", BigDecimal.class)));
        assertEquals(10L, jdbc.queryForObject("SELECT reported_by FROM inventory WHERE inventory_id=1", Long.class));
        // 之後成本改了，舊紀錄的差異金額不變
        jdbc.update("UPDATE inventory SET unit_cost=9 WHERE inventory_id=1");
        mvc.perform(get("/api/inventory-counts").with(login("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].diffCost").value(-9.0));
    }

    @Test
    void resendingSameRequestDoesNotDuplicate() throws Exception {
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":90}]}";
        submit("c_staff", body).andExpect(status().isCreated());
        submit("c_staff", body).andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        // 別人拿同一個 requestId 送出：拒絕
        submit("c_other", body).andExpect(status().isConflict());
    }

    @Test
    void invalidBatchRecordsNothing() throws Exception {
        String id = UUID.randomUUID().toString();
        // 第二項原料不存在：整批不記錄
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":90},{\"inventoryId\":999,\"countedQty\":1}]}")
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        assertEquals(null, jdbc.queryForObject("SELECT reported_qty FROM inventory WHERE inventory_id=1", BigDecimal.class));
        // 同一項重複、負數、超過兩位小數、空清單、前端帶 staffId：400
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":1},{\"inventoryId\":1,\"countedQty\":2}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":-1}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":1.234}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"lines\":[]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"staffId\":11,\"lines\":[{\"inventoryId\":1,\"countedQty\":1}]}").andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
    }

    @Test
    void onlyPersonalAccountsCanCount() throws Exception {
        submit("c_pos", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"inventoryId\":1,\"countedQty\":90}]}")
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory-counts").with(login("c_pos", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory-counts")).andExpect(status().isUnauthorized());
    }

    @Test
    void integerOnlyItemsRejectDecimals() throws Exception {
        jdbc.update("UPDATE inventory SET allow_decimal=FALSE WHERE inventory_id=1");
        mvc.perform(get("/api/inventory-counts/items").with(login("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inventoryId").value(1))
                .andExpect(jsonPath("$[0].allowDecimal").value(false))
                .andExpect(jsonPath("$[0].packUnit").value("袋"))
                .andExpect(jsonPath("$[4].allowDecimal").value(true));
        // 紙杯只能整數：95.5 拒絕，整批不記錄；95.00 視為整數可以
        submit("c_staff", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"inventoryId\":5,\"countedQty\":1.5},{\"inventoryId\":1,\"countedQty\":95.5}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("「中杯紙杯」只能填整數"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        submit("c_staff", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"inventoryId\":5,\"countedQty\":1.5},{\"inventoryId\":1,\"countedQty\":95.00}]}")
                .andExpect(status().isCreated());
    }

    @Test
    void historyRangeIsLimited() throws Exception {
        mvc.perform(get("/api/inventory-counts?from=2026-01-01&to=2026-03-01").with(login("c_staff", PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventory-counts?from=2026-03-02&to=2026-03-01").with(login("c_staff", PASSWORD)))
                .andExpect(status().isBadRequest());
    }
}
