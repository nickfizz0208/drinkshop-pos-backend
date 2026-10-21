package com.example.quiz_1150720;

import com.example.quiz_1150720.entity.QuizUser;
import com.example.quiz_1150720.repository.QuizUserRepository;
import com.example.quiz_1150720.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Date;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:security;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class SecurityIntegrationTest {
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired QuizUserRepository users;
    @Autowired JwtUtil jwt;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    String userAccount;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        userAccount = "test-" + UUID.randomUUID();
        users.saveAndFlush(new QuizUser(userAccount, "hash", "Member", "0", userAccount + "@test.local"));
    }

    private MockHttpServletRequestBuilder member(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(userAccount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    void memberCannotManageQuiz(String action) throws Exception {
        mvc.perform(member(post("/api/quiz/" + action)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void guestCannotReadDetailsOrSubmit() throws Exception {
        mvc.perform(get("/api/quiz/statistics/submissions/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/quiz/submit").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void memberCannotReadOtherPeoplesDetails() throws Exception {
        mvc.perform(member(get("/api/quiz/statistics/submissions/1"))).andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadDetailsAndCreateQuiz() throws Exception {
        String header = "Bearer " + jwt.generateToken("admin");
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", header))
            .andExpect(status().isOk());
        mvc.perform(post("/api/quiz/create").header("Authorization", header)
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"Security test","description":"test","startDate":"2026-10-21","endDate":"2026-10-28",
             "isPublished":true,"questions":[
               {"questionNo":1,"title":"Parent","type":"SINGLE","isRequired":true,"options":[{"optionNo":1,"optionText":"Yes"}]},
               {"questionNo":2,"title":"Text","type":"TEXT","isRequired":true,"parentQuestionNo":1,"triggerOptionNo":1}]}
            """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.questions[1].parentQuestionNo").value(1));
        Long quizId = jdbc.queryForObject("SELECT id FROM quiz WHERE title = 'Security test'", Long.class);
        mvc.perform(get("/api/quiz/detail/" + quizId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.questions[1].triggerOptionNo").value(1));
    }

    @Test
    void invalidAndExpiredTokensReturn401() throws Exception {
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer broken"))
            .andExpect(status().isUnauthorized());
        String forged = new JwtUtil("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=").generateToken("admin");
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer " + forged))
            .andExpect(status().isUnauthorized());
        String expired = Jwts.builder().subject("admin").expiration(new Date(1))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer " + expired))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void signedRoleClaimDoesNotPromoteMemberAndDeletedAccountsAreRejected() throws Exception {
        String promoted = Jwts.builder().subject(userAccount).claim("role", "admin")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer " + promoted))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer " + jwt.generateToken("missing")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void publicSummaryContainsOnlyAggregateData() throws Exception {
        mvc.perform(get("/api/quiz/statistics/summary/1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalResponses").isNumber())
            .andExpect(jsonPath("$.data.userAccount").doesNotExist()).andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void staleTokenDoesNotBlockLoggingInAgain() throws Exception {
        mvc.perform(post("/api/user/login").servletPath("/api/user/login")
            .header("Authorization", "Bearer expired-or-invalid")
            .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"user02\",\"password\":\"123123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.account").value("user02"));
    }

    @Test
    void accountNameContainingAdminDoesNotGrantAdminRole() throws Exception {
        String account = "admin-" + UUID.randomUUID();
        users.saveAndFlush(new QuizUser(account, "hash", "Admin impostor", "0", account + "@test.local"));
        mvc.perform(get("/api/quiz/statistics/submissions/1").header("Authorization", "Bearer " + jwt.generateToken(account)))
            .andExpect(status().isForbidden());
    }

    @Test
    void personalRecordsUseAuthenticatedAccountAndIgnoreRequestedAccount() throws Exception {
        jdbc.update("INSERT INTO quiz_submission(quiz_id,user_account,submit_time) VALUES(1,?,CURRENT_TIMESTAMP)", userAccount);
        jdbc.update("INSERT INTO quiz_submission(quiz_id,user_account,submit_time) VALUES(1,'admin',CURRENT_TIMESTAMP)");
        mvc.perform(member(get("/api/quiz/my-submissions").param("account", "admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].userAccount").value(userAccount));
    }

    @Test
    void memberCanSubmitAndCorsPreflightWorks() throws Exception {
        jdbc.update("INSERT INTO quiz(title,description,start_date,end_date,is_published) VALUES(?, 'test', CURRENT_DATE, CURRENT_DATE, TRUE)", userAccount);
        Long quizId = jdbc.queryForObject("SELECT id FROM quiz WHERE title = ?", Long.class, userAccount);
        jdbc.update("INSERT INTO question(quiz_id,question_no,title,type,is_required) VALUES(?,1,'Text','TEXT',FALSE)", quizId);
        Long questionId = jdbc.queryForObject("SELECT id FROM question WHERE quiz_id = ?", Long.class, quizId);
        mvc.perform(member(post("/api/quiz/submit")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"quizId\":" + quizId + ",\"answers\":[{\"questionId\":" + questionId + ",\"answerText\":\"hello\"}]}"))
            .andExpect(status().isOk());
        mvc.perform(options("/api/quiz/create").header("Origin", "http://localhost:4200")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }
}
