package com.example.drinkshop;

import com.example.drinkshop.entity.ChecklistItem.Frequency;
import com.example.drinkshop.service.ChecklistService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 檢查表 API 整合測試：項目管理權限、打勾／取消、每期完成次數。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:pos_checklist_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-checklist"
})
@AutoConfigureMockMvc
class ChecklistApiTest {
    private static final String PASSWORD = "pass1234";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"), new ClassPathResource("data-h2.sql"))
                .execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'c_staff',?,'測試員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'c_manager',?,'測試店長','MANAGER',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (12,'c_left',?,'離職員工','STAFF',FALSE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (20,'c_pos',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
        // 今天開班，測試員工與店長打卡上班（打勾的人必須在班）
        var now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        jdbc.update("INSERT INTO daily_shift(business_date,status,opened_by,opened_at,petty_cash,roles_json) VALUES (?,'OPEN',11,?,2000,'[]')", now.toLocalDate(), now);
        jdbc.update("INSERT INTO shift_staff(business_date,staff_id,clock_in_at) VALUES (?,10,?)", now.toLocalDate(), now);
        jdbc.update("INSERT INTO shift_staff(business_date,staff_id,clock_in_at) VALUES (?,11,?)", now.toLocalDate(), now);
    }

    private long createItem(String json) throws Exception {
        var body = mvc.perform(post("/api/checklist/items").with(httpBasic("c_manager", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("itemId").asLong();
    }

    @Test
    void onlyManagerCanManageItems() throws Exception {
        mvc.perform(post("/api/checklist/items").with(httpBasic("c_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\"}"))
                .andExpect(status().isForbidden());

        long id = createItem("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\"}");
        mvc.perform(get("/api/checklist/items").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].targetCount").value(1));

        // 停用後預設清單看不到，includeInactive 才看得到
        mvc.perform(put("/api/checklist/items/" + id).with(httpBasic("c_manager", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\",\"targetCount\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/checklist/items").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/checklist/items?includeInactive=true").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void checkUncheckAndProgress() throws Exception {
        long bar = createItem("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\",\"targetCount\":2}");
        createItem("{\"name\":\"清冰箱\",\"frequency\":\"WEEKLY\"}");

        var first = mvc.perform(post("/api/checklist/records").with(httpBasic("c_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffName").value("測試員工"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_pos", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":11}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffName").value("測試店長"));

        mvc.perform(get("/api/checklist").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].doneCount").value(2))
                .andExpect(jsonPath("$[0].completed").value(true))
                .andExpect(jsonPath("$[1].doneCount").value(0))
                .andExpect(jsonPath("$[1].completed").value(false));

        long recordId = mapper.readTree(first).get("recordId").asLong();
        mvc.perform(delete("/api/checklist/records/" + recordId).with(httpBasic("c_staff", PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/checklist").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(jsonPath("$[0].doneCount").value(1))
                .andExpect(jsonPath("$[0].completed").value(false));
    }

    @Test
    void rejectsUnknownOrInactiveStaff() throws Exception {
        long bar = createItem("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\"}");
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_pos", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_pos", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":12}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsStaffNotOnDuty() throws Exception {
        long bar = createItem("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\"}");
        jdbc.update("UPDATE shift_staff SET clock_out_at=clock_in_at WHERE staff_id=10");
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":10}"))
                .andExpect(status().isConflict());
    }

    @Test
    void personalAccountOnlyRecordsAndUndoesOwn() throws Exception {
        long bar = createItem("{\"name\":\"刷吧檯\",\"frequency\":\"DAILY\",\"targetCount\":3}");
        // 不帶 staffId：記成自己
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffId").value(10));
        // 替別人記：拒絕
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + ",\"staffId\":11}"))
                .andExpect(status().isForbidden());
        // POS 不選人：拒絕
        mvc.perform(post("/api/checklist/records").with(httpBasic("c_pos", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + "}"))
                .andExpect(status().isBadRequest());

        var managerRecord = mvc.perform(post("/api/checklist/records").with(httpBasic("c_manager", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":" + bar + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long managerRecordId = mapper.readTree(managerRecord).get("recordId").asLong();
        // 員工不能取消店長的紀錄；POS 可以
        mvc.perform(delete("/api/checklist/records/" + managerRecordId).with(httpBasic("c_staff", PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/checklist/records/" + managerRecordId).with(httpBasic("c_pos", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void staffListExcludesPosAccount() throws Exception {
        mvc.perform(get("/api/staff").with(httpBasic("c_staff", PASSWORD)))
                .andExpect(jsonPath("$[?(@.staffId == 20)]").isEmpty())
                .andExpect(jsonPath("$[?(@.staffId == 10)]").isNotEmpty());
    }

    @Test
    void periodFormat() {
        assertThat(ChecklistService.period(Frequency.DAILY, LocalDate.of(2026, 11, 5))).isEqualTo("2026-11-05");
        assertThat(ChecklistService.period(Frequency.WEEKLY, LocalDate.of(2026, 11, 5))).isEqualTo("2026-W45");
        // 跨年週：2027-01-01 屬於 2026 年第 53 週
        assertThat(ChecklistService.period(Frequency.WEEKLY, LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
    }
}
