package com.example.drinkshop;

import com.example.drinkshop.service.ScheduleService;
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

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 排班 API 整合測試。固定使用 2026-11-09（週一）那一週。 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "pos.demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:pos_schedule_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "pos.photo-directory=build/test-photos-schedule"
})
@AutoConfigureMockMvc
class ScheduleApiTest {
    private static final String PASSWORD = "pass1234";
    private static final String WEEK = "2026-11-11"; // 週三，所在週為 11/09–11/15

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
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'p_staff',?,'測試員工','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'p_manager',?,'測試店長','MANAGER',TRUE)", passwords.encode(PASSWORD));
    }

    private ResultActions asManager(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String json) throws Exception {
        if (json != null) b = b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b.with(httpBasic("p_manager", PASSWORD)));
    }

    private long createTemplate() throws Exception {
        var body = asManager(post("/api/schedule/templates"), "{\"name\":\"早班\",\"startTime\":\"09:30\",\"endTime\":\"15:00\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("templateId").asLong();
    }

    @Test
    void staffListOnlyActive() throws Exception {
        jdbc.update("UPDATE staff_account SET active=FALSE WHERE staff_id=10");
        mvc.perform(get("/api/staff").with(httpBasic("p_manager", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.staffId == 10)]").isEmpty())
                .andExpect(jsonPath("$[?(@.staffId == 11)].name").value("測試店長"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void weekStartIsMonday() {
        assertThat(ScheduleService.weekStart(LocalDate.of(2026, 11, 11))).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(ScheduleService.weekStart(LocalDate.of(2026, 11, 15))).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void onlyManagerCanManage() throws Exception {
        mvc.perform(post("/api/schedule/templates").with(httpBasic("p_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"早班\",\"startTime\":\"09:30\",\"endTime\":\"15:00\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/schedule/entries").with(httpBasic("p_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workDate\":\"2026-11-10\",\"staffId\":10,\"startTime\":\"09:00\",\"endTime\":\"12:00\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/schedule/week/publish?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void templateAndManualTimesAndValidation() throws Exception {
        long early = createTemplate();
        // 選班別：時間取班別
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-10\",\"staffId\":10,\"templateId\":" + early + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.templateName").value("早班"))
                .andExpect(jsonPath("$.entry.startTime").value("09:30:00"))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        // 手動填時間
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-11\",\"staffId\":10,\"startTime\":\"18:00\",\"endTime\":\"21:00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.templateId").doesNotExist());
        // 沒班別也沒時間、時間顛倒
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-11\",\"staffId\":10}")
                .andExpect(status().isBadRequest());
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-11\",\"staffId\":10,\"startTime\":\"18:00\",\"endTime\":\"17:00\"}")
                .andExpect(status().isBadRequest());

        // 修改班別時間不影響已排的班
        asManager(put("/api/schedule/templates/" + early), "{\"name\":\"早班\",\"startTime\":\"08:00\",\"endTime\":\"14:00\",\"active\":true}")
                .andExpect(status().isOk());
        asManager(get("/api/schedule/week?date=" + WEEK), null)
                .andExpect(jsonPath("$.entries[0].startTime").value("09:30:00"));
    }

    @Test
    void overlapOnlyWarns() throws Exception {
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-10\",\"staffId\":10,\"startTime\":\"09:00\",\"endTime\":\"15:00\"}")
                .andExpect(status().isCreated());
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-10\",\"staffId\":10,\"startTime\":\"14:00\",\"endTime\":\"20:00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings", hasSize(1)));
        // 接班不算重疊
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-11\",\"staffId\":10,\"startTime\":\"09:00\",\"endTime\":\"15:00\"}");
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-11\",\"staffId\":10,\"startTime\":\"15:00\",\"endTime\":\"20:00\"}")
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        asManager(get("/api/schedule/week?date=" + WEEK), null)
                .andExpect(jsonPath("$.entries", hasSize(4)))
                .andExpect(jsonPath("$.warnings", hasSize(1)));

        // 有重疊不能發布
        asManager(post("/api/schedule/week/publish?date=" + WEEK), null)
                .andExpect(status().isConflict());
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.published").value(false));
    }

    @Test
    void publishVisibilityAndModifiedFlag() throws Exception {
        var body = asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-10\",\"staffId\":10,\"startTime\":\"09:00\",\"endTime\":\"15:00\"}")
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).get("entry").get("scheduleId").asLong();

        // 未發布：員工看不到，店長看得到
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.entries", hasSize(0)));
        asManager(get("/api/schedule/week?date=" + WEEK), null)
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.entries", hasSize(1)));

        asManager(post("/api/schedule/week/publish?date=" + WEEK), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.modifiedAfterPublish").value(false));
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.entries", hasSize(1)));

        // 發布後修改：員工看到的仍是舊版，店長看到「有尚未發布的修改」
        asManager(delete("/api/schedule/entries/" + id), null).andExpect(status().isOk());
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-12\",\"staffId\":10,\"startTime\":\"18:00\",\"endTime\":\"21:00\"}")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].workDate").value("2026-11-10"))
                .andExpect(jsonPath("$.modifiedAfterPublish").value(false));   // 員工不會看到這個標記
        asManager(get("/api/schedule/week?date=" + WEEK), null)
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].workDate").value("2026-11-12"))
                .andExpect(jsonPath("$.modifiedAfterPublish").value(true));

        // 再發布：員工看到新版，標記清除
        asManager(post("/api/schedule/week/publish?date=" + WEEK), null)
                .andExpect(jsonPath("$.modifiedAfterPublish").value(false));
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].workDate").value("2026-11-12"));

        // 刪光後發布：員工看到空白週
        long id2 = mapper.readTree(asManager(get("/api/schedule/week?date=" + WEEK), null)
                .andReturn().getResponse().getContentAsString()).get("entries").get(0).get("scheduleId").asLong();
        asManager(delete("/api/schedule/entries/" + id2), null).andExpect(status().isOk());
        asManager(post("/api/schedule/week/publish?date=" + WEEK), null).andExpect(status().isOk());
        mvc.perform(get("/api/schedule/week?date=" + WEEK).with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void copyPreviousWeekOnlyIntoEmptyWeek() throws Exception {
        // 上週（11/02–11/08）排兩個班
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-03\",\"staffId\":10,\"startTime\":\"09:00\",\"endTime\":\"15:00\"}");
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-05\",\"staffId\":11,\"startTime\":\"15:00\",\"endTime\":\"22:00\"}");

        asManager(post("/api/schedule/week/copy-previous?date=" + WEEK), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].workDate").value("2026-11-10"))
                .andExpect(jsonPath("$.entries[1].workDate").value("2026-11-12"))
                .andExpect(jsonPath("$.published").value(false));

        // 本週已有班 → 不能再複製
        asManager(post("/api/schedule/week/copy-previous?date=" + WEEK), null)
                .andExpect(status().isConflict());
        // 上週沒有班 → 不能複製
        asManager(post("/api/schedule/week/copy-previous?date=2026-12-02"), null)
                .andExpect(status().isConflict());
    }

    // ---------- 標籤 ----------

    @Test
    void defaultTagsAndOnlyManagerCanManageTags() throws Exception {
        // 預設四個標籤，所有登入帳號都能看；休息有說明
        mvc.perform(get("/api/schedule/tags").with(httpBasic("p_staff", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].name").value("開班"))
                .andExpect(jsonPath("$[3].name").value("休息"))
                .andExpect(jsonPath("$[3].description").value("這段班可以中間休息半小時"));
        // 一般員工不能新增
        mvc.perform(post("/api/schedule/tags").with(httpBasic("p_staff", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"外送\"}"))
                .andExpect(status().isForbidden());
        // 店長新增、改名、停用
        var body = asManager(post("/api/schedule/tags"), "{\"name\":\" 外送 \",\"description\":\"  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("外送"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).get("tagId").asLong();
        asManager(put("/api/schedule/tags/" + id), "{\"name\":\"外送支援\",\"description\":\"負責外送單\",\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        // 停用的不在預設清單，includeInactive 才看得到
        mvc.perform(get("/api/schedule/tags").with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(4)));
        mvc.perform(get("/api/schedule/tags?includeInactive=true").with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void entryTagsAreSavedPublishedAndCopied() throws Exception {
        // 多個標籤：去重、依編號排序；時間必填
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-03\",\"staffId\":10,\"startTime\":\"08:00\",\"endTime\":\"16:00\",\"tagIds\":[4,1,4]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.tagIds[0]").value(1))
                .andExpect(jsonPath("$.entry.tagIds[1]").value(4))
                .andExpect(jsonPath("$.entry.tagNames[0]").value("開班"))
                .andExpect(jsonPath("$.entry.tagNames[1]").value("休息"));
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-03\",\"staffId\":11,\"tagIds\":[3]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請填寫上班與下班時間"));
        // 不存在的標籤
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-03\",\"staffId\":11,\"startTime\":\"16:00\",\"endTime\":\"22:00\",\"tagIds\":[99]}")
                .andExpect(status().isBadRequest());
        // 沒有標籤也可以
        asManager(post("/api/schedule/entries"), "{\"workDate\":\"2026-11-03\",\"staffId\":11,\"startTime\":\"16:00\",\"endTime\":\"22:00\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.tagIds", hasSize(0)));

        // 發布後員工看得到標籤
        asManager(post("/api/schedule/week/publish?date=2026-11-03"), null).andExpect(status().isOk());
        mvc.perform(get("/api/schedule/week?date=2026-11-03").with(httpBasic("p_staff", PASSWORD)))
                .andExpect(jsonPath("$.entries[0].tagNames[0]").value("開班"))
                .andExpect(jsonPath("$.entries[0].tagNames[1]").value("休息"));

        // 複製上週帶著標籤
        asManager(post("/api/schedule/week/copy-previous?date=" + WEEK), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].workDate").value("2026-11-10"))
                .andExpect(jsonPath("$.entries[0].tagIds[1]").value(4));

        // 改標籤名稱：舊班表顯示新名稱
        asManager(put("/api/schedule/tags/4"), "{\"name\":\"可休息\",\"description\":\"這段班可以中間休息半小時\",\"active\":true}")
                .andExpect(status().isOk());
        asManager(get("/api/schedule/week?date=2026-11-03"), null)
                .andExpect(jsonPath("$.entries[0].tagNames[1]").value("可休息"));
    }
}
