package com.example.drinkshop;

import com.example.drinkshop.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 登入 API 與 JWT 驗證 */
@SpringBootTest(properties = {
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:pos_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "pos.photo-directory=build/test-photos-auth"
})
@AutoConfigureMockMvc
class AuthApiTest {
    private static final String PASSWORD = "Test-password-123";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;

    @BeforeEach
    void setup() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"), new ClassPathResource("data-h2.sql")).execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (901,'a_pos',?,'門市 POS','POS',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (902,'a_staff',?,'王小明','STAFF',TRUE)", passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (903,'a_left',?,'離職','STAFF',FALSE)", passwords.encode(PASSWORD));
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test void loginReturnsTokenAndStaffWithoutPassword() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"a_staff\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isString())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.staff.staffId").value(902))
            .andExpect(jsonPath("$.staff.name").value("王小明"))
            .andExpect(jsonPath("$.staff.level").value("STAFF"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("password"), "回應不能包含密碼或雜湊");
        assertFalse(body.contains("$2a$"), "回應不能包含密碼雜湊");
    }

    @Test void tokenWorksForProtectedApis() throws Exception {
        String token = login("a_pos", PASSWORD);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.staffId").value(901));
        mvc.perform(get("/api/recipes").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test void wrongPasswordUnknownAndDisabledAccountsGetSameMessage() throws Exception {
        for (String[] c : new String[][]{{"a_staff", "wrong"}, {"nobody", PASSWORD}, {"a_left", PASSWORD}}) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("username", c[0], "password", c[1]))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("帳號或密碼錯誤"));
        }
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"a_staff\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void basicAuthIsNoLongerAccepted() throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString(("a_pos:" + PASSWORD).getBytes());
        mvc.perform(get("/api/auth/me").header("Authorization", "Basic " + basic))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("請重新登入"));
    }

    @Test void tamperedOrForgedTokensAreRejected() throws Exception {
        String token = login("a_staff", PASSWORD);
        String[] parts = token.split("\\.");
        var enc = java.util.Base64.getUrlEncoder().withoutPadding();
        // 把帳號改成 POS 帳號但沿用原簽章
        String forgedPayload = enc.encodeToString("{\"sub\":\"a_pos\",\"exp\":9999999999}".getBytes());
        String alg_none = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        for (String bad : new String[]{parts[0] + "." + forgedPayload + "." + parts[2],
                                       alg_none + "." + forgedPayload + ".",
                                       token.substring(0, token.length() - 2) + "xx",
                                       "not-a-token", ""}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
        }
    }

    @Test void tokenStopsWorkingOnceAccountIsDisabled() throws Exception {
        String token = login("a_staff", PASSWORD);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("UPDATE staff_account SET active=FALSE WHERE staff_id=902");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void expiredTokenIsRejectedAndPosTokenLastsLonger() {
        Instant start = Instant.parse("2026-11-07T00:00:00Z");
        var mapper = new ObjectMapper();
        var issuer = new JwtService("0123456789abcdef0123456789abcdef", Duration.ofHours(16), Duration.ofHours(2),
            Clock.fixed(start, ZoneOffset.UTC), mapper);
        String staffToken = issuer.issue("a_staff", "STAFF").value();
        String posToken = issuer.issue("a_pos", "POS").value();
        var threeHoursLater = new JwtService("0123456789abcdef0123456789abcdef", Duration.ofHours(16), Duration.ofHours(2),
            Clock.fixed(start.plus(Duration.ofHours(3)), ZoneOffset.UTC), mapper);
        assertTrue(threeHoursLater.verify(staffToken).isEmpty(), "個人帳號 2 小時後過期");
        assertEquals("a_pos", threeHoursLater.verify(posToken).orElseThrow(), "POS 帳號 16 小時內有效");
        var otherKey = new JwtService("ffffffffffffffffffffffffffffffff", Duration.ofHours(16), Duration.ofHours(2),
            Clock.fixed(start, ZoneOffset.UTC), mapper);
        assertTrue(otherKey.verify(posToken).isEmpty(), "不同密鑰簽的 token 不能用");
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", Duration.ofHours(1), Duration.ofHours(1),
            Clock.systemUTC(), mapper));
    }
}
