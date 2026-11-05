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

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 上下班打卡 API 整合測試。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:pos_shift_staff_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-shift-staff"
})
@AutoConfigureMockMvc
class ShiftStaffApiTest {
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
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'s_staff',?,'測試員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'s_manager',?,'測試店長','MANAGER',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (12,'s_left',?,'離職員工','STAFF',FALSE)", passwords.encode(PASSWORD));
    }

    private void openToday() {
        var now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        jdbc.update("INSERT INTO daily_shift(business_date,status,opened_by,opened_at,petty_cash,roles_json) VALUES (?,'OPEN',11,?,2000,'[]')", now.toLocalDate(), now);
    }

    private org.springframework.test.web.servlet.ResultActions clock(String action, long staffId) throws Exception {
        return mvc.perform(post("/api/shift-staff/" + action).with(httpBasic("s_staff", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":" + staffId + "}"));
    }

    @Test
    void cannotClockInBeforeShiftOpens() throws Exception {
        clock("clock-in", 10).andExpect(status().isConflict());
    }

    @Test
    void clockInOutAndMultipleTimesPerDay() throws Exception {
        openToday();
        clock("clock-in", 10).andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffName").value("測試員工"))
                .andExpect(jsonPath("$.onDuty").value(true));
        // 還在班不能重複上班打卡
        clock("clock-in", 10).andExpect(status().isConflict());
        mvc.perform(get("/api/shift-staff/on-duty").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)));

        clock("clock-out", 10).andExpect(status().isOk()).andExpect(jsonPath("$.onDuty").value(false));
        clock("clock-out", 10).andExpect(status().isConflict());
        mvc.perform(get("/api/shift-staff/on-duty").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(0)));

        // 同一天可以再上班
        clock("clock-in", 10).andExpect(status().isCreated());
        mvc.perform(get("/api/shift-staff").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void rejectsUnknownOrInactiveStaff() throws Exception {
        openToday();
        clock("clock-in", 999).andExpect(status().isBadRequest());
        clock("clock-in", 12).andExpect(status().isBadRequest());
    }

    @Test
    void onlyManagerCanEditOrDelete() throws Exception {
        openToday();
        var body = clock("clock-in", 10).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).get("shiftStaffId").asLong();
        var clockIn = mapper.readTree(body).get("clockInAt").asText();
        String update = "{\"clockInAt\":\"" + clockIn + "\",\"clockOutAt\":\"" +
                LocalDateTime.parse(clockIn).plusHours(4) + "\"}";

        mvc.perform(put("/api/shift-staff/" + id).with(httpBasic("s_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/shift-staff/" + id).with(httpBasic("s_manager", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onDuty").value(false))
                .andExpect(jsonPath("$.updatedBy").value(11));

        // 下班早於上班
        String bad = "{\"clockInAt\":\"" + clockIn + "\",\"clockOutAt\":\"" +
                LocalDateTime.parse(clockIn).minusHours(1) + "\"}";
        mvc.perform(put("/api/shift-staff/" + id).with(httpBasic("s_manager", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/shift-staff/" + id).with(httpBasic("s_staff", PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/shift-staff/" + id).with(httpBasic("s_manager", PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/shift-staff").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
