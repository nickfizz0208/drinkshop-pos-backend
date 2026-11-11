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

/** 原料盤點：記錄差異並把帳面改成實點；只限個人帳號；重送不重複；同一批全部成功或全部不記錄。 */
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
    void countIsRecordedWithSnapshotAndBookQtyBecomesCounted() throws Exception {
        String id = UUID.randomUUID().toString();
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"note\":\"月底盤點\",\"lines\":["
                + "{\"inventoryId\":1,\"countedQty\":95},{\"inventoryId\":5,\"countedQty\":103}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].inventoryId").value(1))
                .andExpect(jsonPath("$[0].bookQty").value(100.0))
                .andExpect(jsonPath("$[0].diffQty").value(-5.0))
                .andExpect(jsonPath("$[0].diffCost").value(-10.0))
                .andExpect(jsonPath("$[0].countedBy").value(10))
                .andExpect(jsonPath("$[0].countedByName").value("盤點員工"))
                .andExpect(jsonPath("$[0].note").value("月底盤點"))
                .andExpect(jsonPath("$[1].diffQty").value(3.0))
                .andExpect(jsonPath("$[1].diffCost").doesNotExist());
        // 一次盤點只有一筆表頭（盤點人、時間、備註只存一次），兩項原料是兩筆明細
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count_line", Integer.class));
        assertEquals("月底盤點", jdbc.queryForObject("SELECT note FROM inventory_count", String.class));
        // 帳面庫存改成實點數量（盤點前的帳面 100 留在明細）；原料主檔的「最新盤點」欄位更新成這次
        assertEquals(0, new BigDecimal("95").compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1", BigDecimal.class)));
        assertEquals(0, new BigDecimal("95").compareTo(jdbc.queryForObject("SELECT reported_qty FROM inventory WHERE inventory_id=1", BigDecimal.class)));
        assertEquals(10L, jdbc.queryForObject("SELECT reported_by FROM inventory WHERE inventory_id=1", Long.class));
        // 之後成本改了，舊紀錄的差異金額不變
        jdbc.update("UPDATE inventory SET unit_cost=9 WHERE inventory_id=1");
        mvc.perform(get("/api/inventory-counts").with(login("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].diffCost").value(-10.0));
    }

    @Test
    void resendingSameRequestDoesNotDuplicate() throws Exception {
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":90}]}";
        submit("c_staff", body).andExpect(status().isCreated());
        submit("c_staff", body).andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count_line", Integer.class));
        // 別人拿同一個 requestId 送出：拒絕
        submit("c_other", body).andExpect(status().isConflict());
    }

    @Test
    void invalidBatchRecordsNothing() throws Exception {
        String id = UUID.randomUUID().toString();
        // 第二項原料不存在：整批不記錄
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":90},{\"inventoryId\":999,\"countedQty\":1}]}")
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        assertEquals(null, jdbc.queryForObject("SELECT reported_qty FROM inventory WHERE inventory_id=1", BigDecimal.class));
        // 同一項重複、負數、超過兩位小數、空清單、前端帶 staffId：400
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":1},{\"inventoryId\":1,\"countedQty\":2}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":-1}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":1.234}]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"lines\":[]}").andExpect(status().isBadRequest());
        submit("c_staff", "{\"requestId\":\"" + id + "\",\"typeId\":1,\"staffId\":11,\"lines\":[{\"inventoryId\":1,\"countedQty\":1}]}").andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
    }

    @Test
    void onlyPersonalAccountsCanCount() throws Exception {
        submit("c_pos", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":90}]}")
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory-counts").with(login("c_pos", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory-counts")).andExpect(status().isUnauthorized());
    }

    @Test
    void countedQuantityMustBeWholeNumber() throws Exception {
        mvc.perform(get("/api/inventory-counts/items").with(login("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inventoryId").value(1))
                .andExpect(jsonPath("$[0].packUnit").value("袋"))
                .andExpect(jsonPath("$[0].allowDecimal").doesNotExist());
        // 盤點一律整數（開封、用到一半的不算）：任何原料填小數都拒絕，整批不記錄；95.00 視為整數可以
        submit("c_staff", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":95},{\"inventoryId\":5,\"countedQty\":1.5}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("「奶料」實點數量只能填整數（開封、用到一半的不算）"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        submit("c_staff", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":5,\"countedQty\":2},{\"inventoryId\":1,\"countedQty\":95.00}]}")
                .andExpect(status().isCreated());
    }

    @Test
    void displayByPackIsReturnedForCountPageAndInventoryList() throws Exception {
        // 芒果：1 籃 = 30 顆，畫面用「幾籃幾顆」顯示；資料庫存顆數
        jdbc.update("INSERT INTO inventory (inventory_id,name,base_unit,pack_unit,per_pack,book_qty,safety_stock,display_by_pack) VALUES (20,'芒果','顆','籃',30,153,60,TRUE)");
        mvc.perform(get("/api/inventory-counts/items").with(login("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayByPack").value(false))
                .andExpect(jsonPath("$[?(@.inventoryId == 20)].displayByPack").value(true))
                .andExpect(jsonPath("$[?(@.inventoryId == 20)].bookQty").value(153.0));
        // 日結與報廢頁用的原料清單（POS 帳號）也帶這個設定
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (13,'c_pos2',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
        mvc.perform(get("/api/inventories").with(login("c_pos2", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.inventoryId == 20)].displayByPack").value(true))
                .andExpect(jsonPath("$[?(@.inventoryId == 1)].displayByPack").value(false));
        // 盤點 5 籃 3 顆 = 153 顆，照樣記錄；帳面不會被這個顯示設定改到
        submit("c_staff", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":20,\"countedQty\":153}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].diffQty").value(0.0));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT display_by_pack FROM inventory WHERE inventory_id=20", Boolean.class));
    }

    @Test
    void historyRangeIsLimited() throws Exception {
        mvc.perform(get("/api/inventory-counts?from=2026-01-01&to=2026-03-01").with(login("c_staff", PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventory-counts?from=2026-03-02&to=2026-03-01").with(login("c_staff", PASSWORD)))
                .andExpect(status().isBadRequest());
    }
}
