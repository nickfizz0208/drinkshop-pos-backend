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
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (13,'s_other',?,'其他員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (14,'s_pos',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
    }

    private void openToday() {
        var now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        jdbc.update("INSERT INTO daily_shift(business_date,status,opened_by,opened_at,petty_cash,roles_json) VALUES (?,'OPEN',11,?,2000,'[]')", now.toLocalDate(), now);
    }

    private org.springframework.test.web.servlet.ResultActions clock(String action, long staffId) throws Exception {
        // 上下班打卡只限 POS 共用帳號
        return mvc.perform(post("/api/shift-staff/" + action).with(httpBasic("s_pos", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":" + staffId + "}"));
    }

    @Test
    void cannotClockInBeforeShiftOpens() throws Exception {
        clock("clock-in", 10).andExpect(status().isConflict());
        mvc.perform(get("/api/shift-staff/today").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$.shiftStatus").value("NONE"))
                .andExpect(jsonPath("$.records", hasSize(0)));
    }

    @Test
    void todayShowsShiftStatusAndRecords() throws Exception {
        openToday();
        clock("clock-in", 10).andExpect(status().isCreated());
        mvc.perform(get("/api/shift-staff/today").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$.shiftStatus").value("OPEN"))
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].onDuty").value(true));
    }

    @Test
    void clockInOutAndMultipleTimesPerDay() throws Exception {
        openToday();
        clock("clock-in", 10).andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffName").value("測試員工"))
                .andExpect(jsonPath("$.onDuty").value(true));
        // 還在班不能重複上班打卡
        clock("clock-in", 10).andExpect(status().isConflict());
        mvc.perform(get("/api/shift-staff/on-duty").with(httpBasic("s_pos", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)));

        clock("clock-out", 10).andExpect(status().isOk()).andExpect(jsonPath("$.onDuty").value(false));
        clock("clock-out", 10).andExpect(status().isConflict());
        mvc.perform(get("/api/shift-staff/on-duty").with(httpBasic("s_pos", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(0)));

        // 同一天可以再上班
        clock("clock-in", 10).andExpect(status().isCreated());
        mvc.perform(get("/api/shift-staff").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void staffOnlySeeOnDutyAndOwnRecords() throws Exception {
        openToday();
        clock("clock-in", 11).andExpect(status().isCreated());   // 店長：在班
        clock("clock-in", 13).andExpect(status().isCreated());   // 另一位員工：上班後下班
        clock("clock-out", 13).andExpect(status().isOk());
        clock("clock-in", 10).andExpect(status().isCreated());   // 自己

        // 一般員工：看得到在班的店長和自己，看不到已下班的別人
        mvc.perform(get("/api/shift-staff/today").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$.canViewAll").value(false))
                .andExpect(jsonPath("$.records", hasSize(2)))
                .andExpect(jsonPath("$.records[?(@.staffId == 13)]").isEmpty());
        mvc.perform(get("/api/shift-staff").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].staffId").value(10));
        mvc.perform(get("/api/shift-staff/mine").with(httpBasic("s_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)));

        // 店長：全部
        mvc.perform(get("/api/shift-staff/today").with(httpBasic("s_manager", PASSWORD)))
                .andExpect(jsonPath("$.canViewAll").value(true))
                .andExpect(jsonPath("$.records", hasSize(3)));
        mvc.perform(get("/api/shift-staff").with(httpBasic("s_manager", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // 權限分級：打卡、看在班只限 POS；自己的打卡紀錄 POS 不能看
    @Test
    void clockIsPosOnlyAndRecordsAreForPersonalAccounts() throws Exception {
        openToday();
        for (String user : java.util.List.of("s_staff", "s_manager")) {
            mvc.perform(post("/api/shift-staff/clock-in").with(httpBasic(user, PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":10}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/shift-staff/on-duty").with(httpBasic(user, PASSWORD)))
                    .andExpect(status().isForbidden());
        }
        for (String path : java.util.List.of("/api/shift-staff/mine", "/api/shift-staff")) {
            mvc.perform(get(path).with(httpBasic("s_pos", PASSWORD))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(httpBasic("s_staff", PASSWORD))).andExpect(status().isOk());
        }
        // 今日狀態 POS 也要用（打卡畫面判斷開班、在班）
        mvc.perform(get("/api/shift-staff/today").with(httpBasic("s_pos", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftStatus").value("OPEN"));
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
