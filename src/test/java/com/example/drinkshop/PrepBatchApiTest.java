package com.example.drinkshop;

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
import org.springframework.test.web.servlet.ResultActions;
import javax.sql.DataSource;
import java.time.*;
import static com.example.drinkshop.TestLogin.login;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 備料（050、051）：誰做了什麼半成品、幾份，半成品報廢與每日總覽。測試資料 data-h2.sql 有半成品 1 茶凍。
@SpringBootTest(properties={
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:prep_batch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class PrepBatchApiTest {
    private static final String PASSWORD="Test-password-123";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"),ZoneId.of("Asia/Taipei")); }
    }

    @BeforeEach
    void setup() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("data-h2.sql")).execute(dataSource);
        addStaff(30,"p_pos","門市 POS","POS");
        addStaff(31,"p_staff","店員小華","STAFF");
        addStaff(32,"p_manager","店長","MANAGER");
        addStaff(33,"p_hq","總部","HQ");
    }
    private void addStaff(long id,String username,String name,String level) {
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (?,?,?,?,?,TRUE)",
            id,username,passwords.encode(PASSWORD),name,level);
    }
    private ResultActions create(String user,String body) throws Exception {
        return mvc.perform(post("/api/prep-batches").with(login(user,PASSWORD)).contentType("application/json").content(body));
    }

    @Test
    void posPicksWhoMadeItAndPersonalAccountRecordsSelf() throws Exception {
        var bookBefore=jdbc.queryForObject("SELECT SUM(book_qty) FROM inventory",java.math.BigDecimal.class);
        // POS 機登記：選煮的人
        create("p_pos","{\"prepId\":1,\"qty\":4,\"madeBy\":31,\"note\":\" 新茶葉 \"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.prepName").value("茶凍"))
            .andExpect(jsonPath("$.madeByName").value("店員小華"))
            .andExpect(jsonPath("$.recordedByName").value("門市 POS"))
            .andExpect(jsonPath("$.businessDate").value("2026-11-23"))
            .andExpect(jsonPath("$.madeAt").value("2026-11-23T11:00"))
            .andExpect(jsonPath("$.note").value("新茶葉"));
        // 個人帳號登記：記成自己（給了別人也一樣）
        create("p_manager","{\"prepId\":1,\"qty\":1.5,\"madeBy\":31}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.madeByName").value("店長"))
            .andExpect(jsonPath("$.recordedByName").value("店長"))
            .andExpect(jsonPath("$.note").value(nullValue()));
        mvc.perform(get("/api/prep-batches").with(login("p_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.businessDate").value("2026-11-23"))
            .andExpect(jsonPath("$.rows.length()").value(2));
        mvc.perform(get("/api/prep-batches?date=2026-11-22").with(login("p_staff",PASSWORD)))
            .andExpect(jsonPath("$.rows.length()").value(0));
        mvc.perform(get("/api/prep-batches/preps").with(login("p_pos",PASSWORD)))
            .andExpect(jsonPath("$[0].name").value("茶凍"));
        // 只做紀錄：不改原料帳面
        assertEquals(0,bookBefore.compareTo(jdbc.queryForObject("SELECT SUM(book_qty) FROM inventory",java.math.BigDecimal.class)));
    }

    @Test
    void wasteIsRecordedAndSummaryShowsMadeUsedWasted() throws Exception {
        create("p_pos","{\"prepId\":1,\"qty\":4,\"madeBy\":31}").andExpect(status().isCreated());
        create("p_staff","{\"prepId\":1,\"qty\":1}").andExpect(status().isCreated());
        // 051：半成品報廢，原因必填；POS 要選處理的人
        mvc.perform(post("/api/prep-batches/waste").with(login("p_pos",PASSWORD)).contentType("application/json")
                .content("{\"prepId\":1,\"qty\":1.5}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/prep-batches/waste").with(login("p_pos",PASSWORD)).contentType("application/json")
                .content("{\"prepId\":1,\"qty\":1.5,\"note\":\"放太久\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/prep-batches/waste").with(login("p_pos",PASSWORD)).contentType("application/json")
                .content("{\"prepId\":1,\"qty\":1.5,\"wastedBy\":31,\"note\":\" 放太久 \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.wastedByName").value("店員小華"))
            .andExpect(jsonPath("$.recordedByName").value("門市 POS"))
            .andExpect(jsonPath("$.note").value("放太久"));
        mvc.perform(get("/api/prep-batches").with(login("p_manager",PASSWORD)))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.wastes.length()").value(1))
            .andExpect(jsonPath("$.summary.length()").value(1))
            .andExpect(jsonPath("$.summary[0].name").value("茶凍"))
            .andExpect(jsonPath("$.summary[0].made").value(5.0))
            .andExpect(jsonPath("$.summary[0].used").value(0.0))
            .andExpect(jsonPath("$.summary[0].wasted").value(1.5))
            .andExpect(jsonPath("$.summary[0].remaining").value(3.5));
        mvc.perform(post("/api/prep-batches/waste").with(login("p_hq",PASSWORD)).contentType("application/json")
                .content("{\"prepId\":1,\"qty\":1,\"note\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBadInputAndHq() throws Exception {
        create("p_pos","{\"prepId\":1,\"qty\":4}").andExpect(status().isBadRequest());                  // POS 沒選人
        create("p_pos","{\"prepId\":1,\"qty\":4,\"madeBy\":30}").andExpect(status().isBadRequest());     // 選了 POS 帳號
        create("p_staff","{\"prepId\":1,\"qty\":0}").andExpect(status().isBadRequest());                 // 份數 0
        create("p_staff","{\"prepId\":1,\"qty\":1.234}").andExpect(status().isBadRequest());             // 三位小數
        create("p_staff","{\"prepId\":999,\"qty\":1}").andExpect(status().isNotFound());                 // 沒有這個半成品
        create("p_hq","{\"prepId\":1,\"qty\":1}").andExpect(status().isForbidden());
        mvc.perform(get("/api/prep-batches").with(login("p_hq",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/prep-batches")).andExpect(status().isUnauthorized());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM prep_batch",Integer.class));
    }
}
