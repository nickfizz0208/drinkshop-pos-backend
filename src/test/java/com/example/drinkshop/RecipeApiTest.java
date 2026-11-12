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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.drinkshop.TestLogin.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 櫃台及個人帳號可查看配方，店長與總部可編輯，離職即失效。
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
    @Autowired ObjectMapper json;

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
        addStaff(24,"r_hq","總部","HQ",true);
        addStaff(25,"r_manager","店長","MANAGER",true);
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
        mvc.perform(get("/api/recipes").with(login("r_trainee",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[*].name",hasItem("珍珠奶茶")));
        mvc.perform(get("/api/recipes").with(login("r_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(header().string("Cache-Control","no-store"));
    }

    @Test
    void searchFindsByName() throws Exception {
        mvc.perform(get("/api/recipes").param("q","珍珠").with(login("r_trainee",PASSWORD)))
            .andExpect(jsonPath("$[*].name",contains("珍珠奶茶")));
    }

    @Test
    void traineeCanOpenAnyRecipe() throws Exception {
        mvc.perform(get("/api/recipes/4").with(login("r_trainee",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipeStatus").value("OK"));
    }

    @Test
    void posAccountCanReadRecipes() throws Exception {
        mvc.perform(get("/api/recipes").with(login("r_pos",PASSWORD)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].name", hasItem("珍珠奶茶")));
        mvc.perform(get("/api/recipes/categories").with(login("r_pos",PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/recipes/4").with(login("r_pos",PASSWORD)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.materials[0].name").value("紅茶包"));
    }

    @Test
    void detailShowsMaterialsAndWatermark() throws Exception {
        // 配方只有材料清單（中大杯共用、不含紙杯），份量寫在 steps
        mvc.perform(get("/api/recipes/4").with(login("r_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.recipeStatus").value("OK"))
            .andExpect(jsonPath("$.materials.length()").value(3))
            .andExpect(jsonPath("$.materials[0].kind").value("INVENTORY"))
            .andExpect(jsonPath("$.materials[0].name").value("紅茶包"))
            .andExpect(jsonPath("$.materials[2].name").value("珍珠"))
            .andExpect(jsonPath("$.materials[0].qty").doesNotExist())
            .andExpect(jsonPath("$.sizes").doesNotExist())
            .andExpect(jsonPath("$.steps").exists())
            .andExpect(jsonPath("$.watermark",startsWith("店員小華 r_staff ")));
    }

    // 半成品：配方裡的半成品附上它由哪些原料做成；沒有材料清單的飲品標示 MISSING
    @Test
    void prepItemsListTheirIngredientsAndMissingRecipeIsMarked() throws Exception {
        jdbc.update("INSERT INTO product_recipe (product_id,prep_id,sort_order) VALUES (1,1,2)");
        mvc.perform(get("/api/recipes/1").with(login("r_staff",PASSWORD)))
            .andExpect(jsonPath("$.materials[1].kind").value("PREP"))
            .andExpect(jsonPath("$.materials[1].name").value("茶凍"))
            .andExpect(jsonPath("$.materials[1].madeFrom[0]").value("紅茶包"))
            .andExpect(jsonPath("$.materials[0].madeFrom.length()").value(0));
        jdbc.update("DELETE FROM product_recipe WHERE product_id=2");
        mvc.perform(get("/api/recipes/2").with(login("r_staff",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipeStatus").value("MISSING"))
            .andExpect(jsonPath("$.materials.length()").value(0));
        mvc.perform(get("/api/recipes").with(login("r_staff",PASSWORD)))
            .andExpect(jsonPath("$[?(@.productId == 2)].recipeStatus").value("MISSING"))
            .andExpect(jsonPath("$[?(@.productId == 1)].recipeStatus").value("OK"));
    }

    @Test
    void leftStaffIsLockedOut() throws Exception {
        mvc.perform(get("/api/recipes").with(login("r_left",PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/recipes/999").with(login("r_staff",PASSWORD))).andExpect(status().isNotFound());
    }

    @Test
    void existingPosApiStillWorks() throws Exception {
        mvc.perform(get("/api/menu").with(login("r_staff",PASSWORD))).andExpect(status().isOk());
    }

    private ObjectNode editBody() throws Exception {
        var current = json.readTree(mvc.perform(get("/api/recipes/4").with(login("r_hq", PASSWORD)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var body = json.createObjectNode();
        body.put("version", current.path("version").asText());
        body.put("steps", "先泡茶，再加入材料。");
        var materials = body.putArray("materials");
        for (var material : current.path("materials")) {
            assertTrue(material.path("portions").isNull());
            materials.addObject().put("kind", material.path("kind").asText())
                .put("id", material.path("id").asLong()).put("portions", 1.5);
        }
        return body;
    }

    private ResultActions save(ObjectNode body, String username) throws Exception {
        return mvc.perform(put("/api/recipes/4").with(login(username, PASSWORD))
            .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    @Test
    void managersAndHqCanSaveAndStaffReadsUpdatedStepsAndPortions() throws Exception {
        var body = editBody();
        mvc.perform(put("/api/recipes/4").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isUnauthorized());
        for (String account : new String[]{"r_trainee", "r_staff", "r_pos"}) {
            save(body, account).andExpect(status().isForbidden());
        }
        save(body, "r_hq").andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        save(body, "r_hq").andExpect(status().isOk());
        mvc.perform(get("/api/recipes/4").with(login("r_staff", PASSWORD)))
            .andExpect(jsonPath("$.steps").value("先泡茶，再加入材料。"))
            .andExpect(jsonPath("$.materials[0].portions").value(1.5))
            .andExpect(jsonPath("$.materials[0].name").value("紅茶包"));
        save(body.put("steps", "過期修改"), "r_hq").andExpect(status().isConflict());
        var saved = json.readTree(mvc.perform(get("/api/recipes/4").with(login("r_manager", PASSWORD)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        body.put("version", saved.path("version").asText()).put("steps", "店長更新的步驟");
        save(body, "r_manager").andExpect(status().isOk()).andExpect(jsonPath("$.steps").value("店長更新的步驟"));
    }

    @Test
    void invalidOrMissingPortionsAndWrongMaterialsDoNotWrite() throws Exception {
        var body = editBody();
        var line = (ObjectNode) body.path("materials").get(0);
        var originalId = line.path("id").asLong();
        line.put("portions", 1.001);
        save(body, "r_hq").andExpect(status().isBadRequest());
        line.remove("portions");
        save(body, "r_hq").andExpect(status().isBadRequest());
        line.putNull("portions").put("id", 999);
        save(body, "r_hq").andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_recipe WHERE product_id=4 AND portions IS NOT NULL", Integer.class));
        assertNotEquals(body.path("steps").asText(),
            jdbc.queryForObject("SELECT recipe_steps FROM product WHERE product_id=4", String.class));
        line.put("id", originalId);
        save(body, "r_hq").andExpect(status().isOk());
    }

    @Test
    void portionWriteFailureRollsBackStepsAndOtherPortions() throws Exception {
        var body = editBody();
        var second = body.path("materials").get(1);
        jdbc.execute("ALTER TABLE product_recipe ADD CHECK (inventory_id <> " + second.path("id").asLong()
            + " OR portions IS NULL)");
        var oldSteps = jdbc.queryForObject("SELECT recipe_steps FROM product WHERE product_id=4", String.class);
        save(body, "r_hq").andExpect(status().isConflict());
        assertEquals(oldSteps, jdbc.queryForObject("SELECT recipe_steps FROM product WHERE product_id=4", String.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_recipe WHERE product_id=4 AND portions IS NOT NULL", Integer.class));
    }

    @Test
    void simultaneousEditsFromSameVersionDoNotOverwriteEachOther() throws Exception {
        var first = editBody();
        var second = first.deepCopy().put("steps", "另一個人的步驟");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return save(first, "r_hq").andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { start.await(); return save(second, "r_manager").andReturn().getResponse().getStatus(); });
            start.countDown();
            assertEquals(java.util.Set.of(200, 409),
                java.util.Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
        }
    }
}
