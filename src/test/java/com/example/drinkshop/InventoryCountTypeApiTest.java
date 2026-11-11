package com.example.drinkshop;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static com.example.drinkshop.TestLogin.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 盤點類型：店長設定、員工選用；只填箱數的類型與原料範圍由後端把關；紀錄保存當時的類型。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "pos.inventory-count.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:pos_count_type_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-count-type"
})
@AutoConfigureMockMvc
class InventoryCountTypeApiTest {
    private static final String PASSWORD = "pass1234";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"), new ClassPathResource("data-h2.sql"))
                .execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'t_staff',?,'盤點員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'t_manager',?,'店長','MANAGER',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (12,'t_pos',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("UPDATE inventory SET book_qty=100 WHERE inventory_id IN (1,3,5)");
    }

    private ResultActions as(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(login(user, PASSWORD)).contentType(MediaType.APPLICATION_JSON));
    }
    private long createType(String json) throws Exception {
        String body = as("t_manager", post("/api/inventory-counts/types").content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("typeId").asLong();
    }
    private ResultActions count(long typeId, String lines) throws Exception {
        return as("t_staff", post("/api/inventory-counts").content(
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":" + typeId + ",\"lines\":[" + lines + "]}"));
    }

    @Test
    void defaultFullCountExistsAndOnlyManagersCanEditTypes() throws Exception {
        as("t_staff", get("/api/inventory-counts/types")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("大盤"))
                .andExpect(jsonPath("$[0].inputMode").value("PACK_AND_LOOSE"))
                .andExpect(jsonPath("$[0].allItems").value(true));
        String body = "{\"name\":\"小盤\",\"inputMode\":\"PACK_ONLY\",\"allItems\":false,\"itemIds\":[1]}";
        as("t_staff", post("/api/inventory-counts/types").content(body)).andExpect(status().isForbidden());
        as("t_pos", get("/api/inventory-counts/types")).andExpect(status().isForbidden());
        as("t_manager", post("/api/inventory-counts/types").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemIds[0]").value(1)).andExpect(jsonPath("$.updatedByName").value("店長"));
        // 同名、沒選原料、原料不存在：拒絕
        as("t_manager", post("/api/inventory-counts/types").content(body)).andExpect(status().isConflict());
        as("t_manager", post("/api/inventory-counts/types").content("{\"name\":\"冰箱\",\"inputMode\":\"PACK_AND_LOOSE\",\"allItems\":false,\"itemIds\":[]}"))
                .andExpect(status().isBadRequest());
        as("t_manager", post("/api/inventory-counts/types").content("{\"name\":\"冰箱\",\"inputMode\":\"PACK_AND_LOOSE\",\"allItems\":false,\"itemIds\":[999]}"))
                .andExpect(status().isBadRequest());
        as("t_manager", post("/api/inventory-counts/types").content("{\"name\":\"冰箱\",\"inputMode\":\"BOGUS\",\"allItems\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void packOnlyTypeAcceptsWholePacksAndOnlyItsItems() throws Exception {
        long quick = createType("{\"name\":\"小盤\",\"inputMode\":\"PACK_ONLY\",\"allItems\":false,\"itemIds\":[1,5]}");
        // 不在類型裡的原料（紅茶包 3）：拒絕
        count(quick, "{\"inventoryId\":3,\"countedQty\":100}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("「紅茶包」不在「小盤」要盤的原料裡"));
        // 紙杯 1 袋 = 50 個：只填箱數，所以要是 50 的倍數
        count(quick, "{\"inventoryId\":1,\"countedQty\":160}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("「小盤」只填箱數：「中杯紙杯」要是 50 的倍數"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        // 3 袋 = 150 個；奶料沒有包裝單位，照基本單位填
        count(quick, "{\"inventoryId\":1,\"countedQty\":150},{\"inventoryId\":5,\"countedQty\":42}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].typeName").value("小盤"))
                .andExpect(jsonPath("$[0].inputMode").value("PACK_ONLY"))
                .andExpect(jsonPath("$[0].diffQty").value(50.0));
        // 小盤也覆蓋帳面
        assertEquals(0, new BigDecimal("150").compareTo(jdbc.queryForObject("SELECT book_qty FROM inventory WHERE inventory_id=1", BigDecimal.class)));
        // 大盤（全部原料、箱數＋零散）：零頭可以
        count(1, "{\"inventoryId\":1,\"countedQty\":160},{\"inventoryId\":3,\"countedQty\":99}").andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].typeName").value("大盤"));
    }

    @Test
    void newItemsJoinAllItemsTypesAutomatically() throws Exception {
        jdbc.update("INSERT INTO inventory (inventory_id,name,base_unit,pack_unit,per_pack,book_qty,safety_stock) VALUES (9,'吸管','支',NULL,NULL,0,0)");
        count(1, "{\"inventoryId\":9,\"countedQty\":30}").andExpect(status().isCreated());
    }

    @Test
    void disabledTypeCannotBeUsedButOldRecordsKeepTheirTypeName() throws Exception {
        long quick = createType("{\"name\":\"小盤\",\"inputMode\":\"PACK_ONLY\",\"allItems\":true}");
        count(quick, "{\"inventoryId\":1,\"countedQty\":100}").andExpect(status().isCreated());
        // 改名並停用
        as("t_manager", put("/api/inventory-counts/types/" + quick).content(
                "{\"name\":\"週盤\",\"inputMode\":\"PACK_AND_LOOSE\",\"allItems\":true,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        count(quick, "{\"inventoryId\":1,\"countedQty\":100}").andExpect(status().isConflict());
        as("t_staff", get("/api/inventory-counts/types")).andExpect(jsonPath("$.length()").value(1));
        as("t_manager", get("/api/inventory-counts/types?includeInactive=true")).andExpect(jsonPath("$.length()").value(2));
        // 舊紀錄仍是當時的「小盤／只填箱數」
        as("t_staff", get("/api/inventory-counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].typeName").value("小盤"))
                .andExpect(jsonPath("$.rows[0].inputMode").value("PACK_ONLY"));
        as("t_staff", put("/api/inventory-counts/types/" + quick).content(
                "{\"name\":\"x\",\"inputMode\":\"PACK_ONLY\",\"allItems\":true}")).andExpect(status().isForbidden());
        as("t_manager", put("/api/inventory-counts/types/999").content(
                "{\"name\":\"x\",\"inputMode\":\"PACK_ONLY\",\"allItems\":true}")).andExpect(status().isNotFound());
    }
}
