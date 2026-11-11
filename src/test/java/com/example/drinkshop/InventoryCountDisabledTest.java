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

import javax.sql.DataSource;
import java.util.UUID;

import static com.example.drinkshop.TestLogin.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 016 尚未升級（設定關閉）：送出盤點回 503 且不寫入，查詢回 enabled=false。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "pos.inventory-count.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:pos_count_off_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-count-off"
})
@AutoConfigureMockMvc
class InventoryCountDisabledTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"), new ClassPathResource("data-h2.sql"))
                .execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'c_staff',?,'盤點員工','STAFF',TRUE)", passwords.encode("pass1234"));
    }

    @Test
    void disabledCountIsRejectedWithoutWriting() throws Exception {
        mvc.perform(post("/api/inventory-counts").with(login("c_staff", "pass1234")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"typeId\":1,\"lines\":[{\"inventoryId\":1,\"countedQty\":90}]}"))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_count", Integer.class));
        mvc.perform(get("/api/inventory-counts").with(login("c_staff", "pass1234")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }
}
