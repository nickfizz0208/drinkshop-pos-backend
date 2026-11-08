package com.example.drinkshop;

import com.example.drinkshop.dto.OrderRequests.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.drinkshop.service.HereService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.drinkshop.TestLogin.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:pos;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "pos.photo-directory=build/test-photos"
})
@AutoConfigureMockMvc
class MemberApiTest {
    private static final String PASSWORD="Test-password-123";
    // HERE 以固定回應替代，測試不使用金鑰、不呼叫外部地圖服務。
    @MockitoBean HereService here;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    @TestConfiguration
    static class TestClock {
        // data-h2.sql 只有 2026-10-28 的開班資料，點餐測試要用同一天。
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"),ZoneId.of("Asia/Taipei"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("data-h2.sql")).execute(dataSource);
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (10,'test_staff',?,'門市 POS','POS',TRUE)",passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (11,'test_manager',?,'測試店長','MANAGER',TRUE)",passwords.encode(PASSWORD));
    }

    private ResultActions as(String user,MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(login(user,PASSWORD)));
    }
    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request,String body) {
        return request.contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8));
    }
    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private ResultActions order(String memberPhone) throws Exception {
        var item=new Item(1L,Size.M,Temperature.COLD,Sugar.HALF,Ice.LESS,1,List.of(),null);
        var request=new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.LINE_PAY,memberPhone,null,null,null,null,null,List.of(item));
        return as("test_staff",post("/api/orders").contentType("application/json").content(json.writeValueAsBytes(request)));
    }
    private void insertMember(String phone,String name) {
        jdbc.update("INSERT INTO member(phone,name) VALUES (?,?)",phone,name);
    }

    @Test void requiresLogin() throws Exception {
        mvc.perform(get("/api/members")).andExpect(status().isUnauthorized());
        mvc.perform(withJson(post("/api/members"),"{\"phone\":\"0912345678\",\"name\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test void createThenGetAndSearch() throws Exception {
        var created=read(as("test_staff",withJson(post("/api/members"),"""
            {"phone":"0912345678","name":"王小明","address":"台北市信義路一段1號","lat":25.0330,"lng":121.5654,"note":"  "}
            """)).andExpect(status().isOk()));
        assertEquals("0912345678",created.get("phone").asText());
        assertEquals("王小明",created.get("name").asText());
        assertFalse(created.get("createdAt").isNull());
        assertTrue(created.get("note").isNull());
        assertEquals(0,created.get("lat").decimalValue().compareTo(new java.math.BigDecimal("25.033")));

        var one=read(as("test_staff",get("/api/members/0912345678")).andExpect(status().isOk()));
        assertEquals("台北市信義路一段1號",one.get("address").asText());

        var byPhone=read(as("test_staff",get("/api/members").param("keyword","5678")).andExpect(status().isOk()));
        assertEquals(1,byPhone.size()); assertEquals("0912345678",byPhone.get(0).get("phone").asText());
        var byName=read(as("test_staff",get("/api/members").param("keyword","測試會員")).andExpect(status().isOk()));
        assertEquals(1,byName.size()); assertEquals("0900000001",byName.get(0).get("phone").asText());
        var all=read(as("test_staff",get("/api/members")).andExpect(status().isOk()));
        assertEquals(2,all.size());
        var none=read(as("test_staff",get("/api/members").param("keyword","%")).andExpect(status().isOk()));
        assertEquals(0,none.size());
    }

    @Test void rejectsDuplicateAndInvalidInput() throws Exception {
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0900000001\",\"name\":\"重複\"}")).andExpect(status().isConflict());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0912345678\"}")).andExpect(status().isBadRequest());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0912345678\",\"name\":\" \"}")).andExpect(status().isBadRequest());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"abc\",\"name\":\"x\"}")).andExpect(status().isBadRequest());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0912345678\",\"name\":\"x\",\"lat\":25}")).andExpect(status().isBadRequest());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0912345678\",\"name\":\"x\",\"lat\":91,\"lng\":121}")).andExpect(status().isBadRequest());
        as("test_staff",withJson(post("/api/members"),"{\"phone\":\"0912345678\",\"name\":\"x\",\"foo\":1}")).andExpect(status().isBadRequest());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member",Integer.class));
    }

    @Test void getReportsMissingAndBadPhone() throws Exception {
        as("test_staff",get("/api/members/0999999999")).andExpect(status().isNotFound());
        as("test_staff",get("/api/members/abc")).andExpect(status().isBadRequest());
        as("test_staff",get("/api/members/0999999999/orders")).andExpect(status().isNotFound());
    }

    @Test void updateChangesDetailsButNeverThePhone() throws Exception {
        var updated=read(as("test_staff",withJson(put("/api/members/0900000001"),"""
            {"phone":"0911111111","name":"新名字","address":null,"lat":null,"lng":null,"note":"改過","createdAt":"x","updatedAt":"y"}
            """)).andExpect(status().isOk()));
        assertEquals("0900000001",updated.get("phone").asText());
        assertEquals("新名字",updated.get("name").asText());
        assertTrue(updated.get("address").isNull());
        assertEquals("新名字",jdbc.queryForObject("SELECT name FROM member WHERE phone='0900000001'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE phone='0911111111'",Integer.class));
        as("test_staff",withJson(put("/api/members/0999999999"),"{\"name\":\"x\"}")).andExpect(status().isNotFound());
        as("test_staff",withJson(put("/api/members/0900000001"),"{\"name\":\"\"}")).andExpect(status().isBadRequest());
    }

    @Test void historyAndDeletionRules() throws Exception {
        insertMember("0922000000","有訂單的會員");
        order("0922000000").andExpect(status().isOk());
        var history=read(as("test_staff",get("/api/members/0922000000/orders")).andExpect(status().isOk()));
        assertEquals(1,history.size());
        assertEquals("0922000000",jdbc.queryForObject("SELECT member_phone FROM pos_order WHERE order_id=?",String.class,history.get(0).get("order_id").asLong()));

        as("test_staff",delete("/api/members/0922000000")).andExpect(status().isForbidden());
        as("test_manager",delete("/api/members/0922000000")).andExpect(status().isConflict());

        insertMember("0933000000","沒有訂單的會員");
        as("test_manager",delete("/api/members/0933000000")).andExpect(status().isOk());
        as("test_staff",get("/api/members/0933000000")).andExpect(status().isNotFound());
        as("test_manager",delete("/api/members/0933000000")).andExpect(status().isNotFound());
    }

    @Test void orderWithUnknownMemberGivesClearMessage() throws Exception {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM pos_order",Integer.class);
        var error=read(order("0988000000").andExpect(status().isNotFound()));
        assertEquals("找不到此會員電話，請先建立會員",error.get("message").asText());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM pos_order",Integer.class));
        insertMember("0988000000","新會員");
        order("0988000000").andExpect(status().isOk());
    }

    @Test void deliveryEndpointsRequireLoginAndReturnConfiguredStore() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/members/0900000001/route")).andExpect(status().isUnauthorized());
        mvc.perform(withJson(post("/api/route"), "{\"lat\":22.6,\"lng\":120.3}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(withJson(post("/api/geo/reverse"), "{\"lat\":22.6,\"lng\":120.3}"))
            .andExpect(status().isUnauthorized());
        as("test_staff", get("/api/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.store.lat").value(22.60388))
            .andExpect(jsonPath("$.store.lng").value(120.29785));
    }

    @Test void temporaryRouteAndReverseUseValidatedCoordinates() throws Exception {
        var lat = new BigDecimal("22.6"); var lng = new BigDecimal("120.3");
        when(here.route(lat, lng)).thenReturn(new HereService.RouteResult(1200, List.of("test-polyline")));
        when(here.reverse(lat, lng)).thenReturn("測試配送地址");
        as("test_staff", withJson(post("/api/route"), "{\"lat\":22.6,\"lng\":120.3}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.distanceM").value(1200))
            .andExpect(jsonPath("$.polylines[0]").value("test-polyline"));
        as("test_staff", withJson(post("/api/geo/reverse"), "{\"lat\":22.6,\"lng\":120.3}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("測試配送地址"));
        as("test_staff", withJson(post("/api/route"), "{\"lat\":91,\"lng\":120.3}"))
            .andExpect(status().isBadRequest());
        as("test_staff", withJson(post("/api/geo/reverse"), "{\"lat\":22.6}"))
            .andExpect(status().isBadRequest());
        verify(here, times(1)).route(lat, lng);
        verify(here, times(1)).reverse(lat, lng);
    }

    @Test void memberGeocodeIsSavedAndReusedForNextRoute() throws Exception {
        var lat = new BigDecimal("22.61"); var lng = new BigDecimal("120.31");
        jdbc.update("UPDATE member SET address='測試地址',lat=NULL,lng=NULL,geo_source=NULL WHERE phone='0900000001'");
        when(here.geocode("測試地址")).thenReturn(new HereService.GeoResult(lat, lng, "測試門牌", true));
        when(here.route(any(), any())).thenReturn(new HereService.RouteResult(1200, List.of()));
        as("test_staff", get("/api/members/0900000001/route")).andExpect(status().isOk())
            .andExpect(jsonPath("$.geoSource").value("GEOCODE"))
            .andExpect(jsonPath("$.needsReview").value(true));
        assertEquals("GEOCODE", jdbc.queryForObject("SELECT geo_source FROM member WHERE phone='0900000001'", String.class));
        as("test_staff", get("/api/members/0900000001/route")).andExpect(status().isOk());
        verify(here, times(1)).geocode("測試地址");
        verify(here, times(2)).route(any(), any());
    }

    @Test void deliveryOrderKeepsAddressAndDistanceAfterMemberChanges() throws Exception {
        var item = new Item(1L, Size.M, Temperature.COLD, Sugar.HALF, Ice.LESS, 1, List.of(), null);
        var request = new Create(UUID.randomUUID(), Source.PHONE_DELIVERY, Payment.LINE_PAY,
            "0900000001", null, new BigDecimal("1200"), null, "這張單的配送地址", null, List.of(item));
        var created = read(as("test_staff", post("/api/orders").contentType("application/json")
            .content(json.writeValueAsBytes(request))).andExpect(status().isOk()));
        long id = created.get("order_id").asLong();
        jdbc.update("UPDATE member SET address='後來修改的地址' WHERE phone='0900000001'");
        as("test_staff", get("/api/orders/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("PHONE_DELIVERY"))
            .andExpect(jsonPath("$.member_phone").value("0900000001"))
            .andExpect(jsonPath("$.address_note").value("這張單的配送地址"))
            .andExpect(jsonPath("$.distance").value(1200));
    }
}
