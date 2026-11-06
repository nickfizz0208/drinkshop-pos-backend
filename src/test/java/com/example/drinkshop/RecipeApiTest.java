package com.example.drinkshop;

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
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 手機端查看配方：不分等級（POS 共用帳號不能看）、離職即失效、浮水印、不快取
// 測試資料 data-h2.sql：經典紅茶、茉香綠茶、經典奶茶是 TRAINEE；珍珠奶茶、芒果冰沙是 STAFF
@SpringBootTest(properties={
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:recipe;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class RecipeApiTest {
    private static final String PASSWORD="Test-password-123";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void setup() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("data-h2.sql")).execute(dataSource);
        addStaff(20,"r_trainee","新進小明","TRAINEE",true);
        addStaff(21,"r_staff","店員小華","STAFF",true);
        addStaff(22,"r_left","離職小美","STAFF",false);
        addStaff(23,"r_pos","門市 POS","POS",true);
    }

    private void addStaff(long id,String username,String name,String level,boolean active) {
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (?,?,?,?,?,?)",
            id,username,passwords.encode(PASSWORD),name,level,active);
    }

    @Test
    void notLoggedInGets401() throws Exception {
        mvc.perform(get("/api/recipes")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyLevelSeesAllRecipes() throws Exception {
        mvc.perform(get("/api/recipes").with(httpBasic("r_trainee",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[*].name",hasItem("珍珠奶茶")));
        mvc.perform(get("/api/recipes").with(httpBasic("r_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(header().string("Cache-Control","no-store"));
    }

    @Test
    void searchFindsByName() throws Exception {
        mvc.perform(get("/api/recipes").param("q","珍珠").with(httpBasic("r_trainee",PASSWORD)))
            .andExpect(jsonPath("$[*].name",contains("珍珠奶茶")));
    }

    @Test
    void traineeCanOpenAnyRecipe() throws Exception {
        mvc.perform(get("/api/recipes/4").with(httpBasic("r_trainee",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipeStatus").value("OK"));
    }

    @Test
    void posAccountCannotSeeRecipes() throws Exception {
        mvc.perform(get("/api/recipes").with(httpBasic("r_pos",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/recipes/categories").with(httpBasic("r_pos",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/recipes/4").with(httpBasic("r_pos",PASSWORD))).andExpect(status().isForbidden());
    }

    @Test
    void detailShowsUnitsAndWatermark() throws Exception {
        mvc.perform(get("/api/recipes/4").with(httpBasic("r_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.recipeStatus").value("OK"))
            .andExpect(jsonPath("$.sizes[0].label").value("中杯"))
            .andExpect(jsonPath("$.sizes[0].lines[0].item").value("中杯紙杯"))
            .andExpect(jsonPath("$.sizes[0].lines[0].unit").value("個"))
            .andExpect(jsonPath("$.sizes[0].lines[2].unit").value("份"))
            .andExpect(jsonPath("$.watermark",startsWith("店員小華 r_staff ")));
    }

    // 合併時發現的問題：用參數寫進 JSON 欄位的文字，會被多包一層引號存成字串，不能被判成格式錯誤
    @Test
    void wrappedRecipeJsonStillReadable() throws Exception {
        jdbc.update("UPDATE product SET recipe_json=? WHERE product_id=1",
            "{\"M\":[{\"inventoryId\":1,\"qty\":1}]}");
        assertTrue(jdbc.queryForObject("SELECT recipe_json FROM product WHERE product_id=1",String.class).startsWith("\""));
        mvc.perform(get("/api/recipes/1").with(httpBasic("r_staff",PASSWORD)))
            .andExpect(jsonPath("$.recipeStatus").value("OK"))
            .andExpect(jsonPath("$.sizes[0].lines[0].item").value("中杯紙杯"));
    }

    @Test
    void leftStaffIsLockedOut() throws Exception {
        mvc.perform(get("/api/recipes").with(httpBasic("r_left",PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/recipes/999").with(httpBasic("r_staff",PASSWORD))).andExpect(status().isNotFound());
    }

    @Test
    void existingPosApiStillWorks() throws Exception {
        mvc.perform(get("/api/menu").with(httpBasic("r_staff",PASSWORD))).andExpect(status().isOk());
    }
}
