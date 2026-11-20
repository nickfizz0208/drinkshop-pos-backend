package com.example.drinkshop;

import com.example.drinkshop.dto.OrderRequests.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;
import static com.example.drinkshop.TestLogin.login;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 停售管理（028）。測試資料 data-h2.sql：
//   原料 3 紅茶包（經典紅茶、經典奶茶、珍珠奶茶，以及半成品 1 茶凍）、6 珍珠（珍珠奶茶、加料 1 珍珠）
//   半成品 1 茶凍（由紅茶包做成；加料 2 茶凍）
@SpringBootTest(properties={
    "spring.config.import=", "pos.demo.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:sale;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class SaleStatusApiTest {
    private static final String PASSWORD="Test-password-123";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"),ZoneId.of("Asia/Taipei")); }
    }

    @BeforeEach
    void setup() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("data-h2.sql")).execute(dataSource);
        addStaff(30,"s_pos","門市 POS","POS");
        addStaff(31,"s_staff","店員小華","STAFF");
        addStaff(32,"s_manager","店長","MANAGER");
        addStaff(33,"s_hq","總部","HQ");
    }
    private void addStaff(long id,String username,String name,String level) {
        jdbc.update("INSERT INTO staff_account(staff_id,username,password_hash,name,level,active) VALUES (?,?,?,?,?,TRUE)",
            id,username,passwords.encode(PASSWORD),name,level);
    }
    private ResultActions put(String user,String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status")
            .with(login(user,PASSWORD)).contentType("application/json").content(body));
    }
    private boolean onSale(String table,String key,long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT on_sale FROM "+table+" WHERE "+key+"=?",Boolean.class,id));
    }

    @Test
    void onlyPosAndManagerCanViewWithLinks() throws Exception {
        mvc.perform(get("/api/sale-status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sale-status").with(login("s_staff",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/sale-status").with(login("s_hq",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/sale-status").with(login("s_manager",PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/sale-status").with(login("s_pos",PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==6)].usedByProducts[*]",contains(4)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==6)].usedByToppings[*]",contains(1)))
            // 紅茶包透過半成品茶凍連到加料茶凍
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==3)].usedByProducts[*]",contains(1,3,4)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==3)].usedByToppings[*]",contains(2)))
            .andExpect(jsonPath("$.preps[0].inventoryIds[*]",contains(3)))
            .andExpect(jsonPath("$.products[?(@.productId==4)].sellable",contains(true)));
    }

    @Test
    void onlyPosAndManagerCanChange() throws Exception {
        put("s_staff","{\"inventories\":[{\"id\":6,\"onSale\":false}]}").andExpect(status().isForbidden());
        put("s_hq","{\"inventories\":[{\"id\":6,\"onSale\":false}]}").andExpect(status().isForbidden());
        assertTrue(onSale("inventory","inventory_id",6));
        put("s_manager","{\"inventories\":[{\"id\":6,\"onSale\":false}]}").andExpect(status().isOk());
        assertFalse(onSale("inventory","inventory_id",6));
    }

    @Test
    void stoppingMaterialStopsLinkedItemsAndRestoringBringsThemBack() throws Exception {
        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.products[?(@.productId==4)].sellable",contains(false)))
            .andExpect(jsonPath("$.products[?(@.productId==4)].onSale",contains(true)))
            .andExpect(jsonPath("$.products[?(@.productId==4)].stoppedBy[*]",contains("珍珠")))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==1)].sellable",contains(false)))
            .andExpect(jsonPath("$.products[?(@.productId==3)].sellable",contains(true)));
        // 飲品自己的停售狀態沒有被改寫
        assertTrue(onSale("product","product_id",4));
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.products[*].product_id",not(hasItem(4))))
            .andExpect(jsonPath("$.products.length()").value(4))
            .andExpect(jsonPath("$.toppings[*].topping_id",contains(2)));

        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":true}]}").andExpect(status().isOk());
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.products.length()").value(5))
            .andExpect(jsonPath("$.toppings.length()").value(2));
    }

    @Test
    void ingredientOfPrepStopsPrepAndItsTopping() throws Exception {
        put("s_pos","{\"inventories\":[{\"id\":3,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preps[0].onSale").value(true))
            .andExpect(jsonPath("$.preps[0].available").value(false))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==2)].stoppedBy[*]",contains("紅茶包")));
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.products[*].product_id",containsInAnyOrder(2,5)))
            .andExpect(jsonPath("$.toppings[*].topping_id",contains(1)));
    }

    @Test
    void stoppingPrepOnlyAffectsItsUsers() throws Exception {
        put("s_pos","{\"preps\":[{\"id\":1,\"onSale\":false}],\"products\":[{\"id\":5,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toppings[?(@.toppingId==2)].stoppedBy[*]",contains("茶凍")));
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.products[*].product_id",contains(1,2,3,4)))
            .andExpect(jsonPath("$.toppings[*].topping_id",contains(1)));
    }

    /** 043：雙Q果（半成品）＝茶凍（半成品，紅茶包做的）＋珍珠（原料）；加料「雙Q果」用這個半成品。 */
    private void addNestedPrep() {
        jdbc.update("INSERT INTO prep_item (prep_id,name,steps,category) VALUES (2,'雙Q果','茶凍、珍珠各半混合','底料')");
        jdbc.update("INSERT INTO prep_item_ingredient (prep_id,sub_prep_id,qty) VALUES (2,1,0.5)");
        jdbc.update("INSERT INTO prep_item_ingredient (prep_id,inventory_id,qty) VALUES (2,6,0.5)");
        jdbc.update("INSERT INTO topping (topping_id,name,price,prep_id) VALUES (3,'雙Q果',10,2)");
    }

    @Test
    void linkedSubPrepStopsUpperPrepAllTheWayUp() throws Exception {
        addNestedPrep();
        mvc.perform(get("/api/sale-status").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].subPrepIds[*]",contains(1)))
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].inventoryIds[*]",contains(6)))
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].ingredients[*].link",contains(true,true)))
            // 紅茶包透過茶凍、雙Q果兩層連到加料雙Q果
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==3)].usedByToppings[*]",contains(2,3)))
            .andExpect(jsonPath("$.preps[?(@.prepId==1)].usedByToppings[*]",contains(2,3)));

        // 下層半成品自己停售：雙Q果跟著不能賣
        put("s_pos","{\"preps\":[{\"id\":1,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].onSale",contains(true)))
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].available",contains(false)))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==3)].stoppedBy[*]",contains("茶凍")));
        put("s_pos","{\"preps\":[{\"id\":1,\"onSale\":true}]}").andExpect(status().isOk());

        // 下層半成品的原料停售：一路連到雙Q果，送單也會擋
        put("s_pos","{\"inventories\":[{\"id\":3,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toppings[?(@.toppingId==3)].stoppedBy[*]",contains("紅茶包")));
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.toppings[*].topping_id",contains(1)));
        var item=new Item(2L,Size.M,TempIce.LESS,Sugar.S5,1,List.of(3L),null);
        var request=new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.LINE_PAY,null,null,null,null,null,null,List.of(item));
        mvc.perform(post("/api/orders").with(login("s_pos",PASSWORD)).contentType("application/json")
            .content(json.writeValueAsBytes(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message",containsString("紅茶包 停售")));
    }

    @Test
    void unlinkedIngredientDoesNotStopPrep() throws Exception {
        addNestedPrep();
        // 044：雙Q果裡的茶凍不連動（例如綠茶凍裡的綠茶）
        jdbc.update("UPDATE prep_item_ingredient SET link=FALSE WHERE prep_id=2 AND sub_prep_id=1");
        mvc.perform(get("/api/sale-status").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].ingredients[?(@.subPrepId==1)].link",contains(false)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==3)].usedByToppings[*]",contains(2)))
            .andExpect(jsonPath("$.preps[?(@.prepId==1)].usedByToppings[*]",contains(2)));

        put("s_pos","{\"preps\":[{\"id\":1,\"onSale\":false}],\"inventories\":[{\"id\":3,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preps[?(@.prepId==2)].available",contains(true)))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==3)].sellable",contains(true)))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==2)].sellable",contains(false)));
        mvc.perform(get("/api/menu").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.toppings[*].topping_id",contains(1,3)));

        // 原料材料也可以不連動
        jdbc.update("UPDATE prep_item_ingredient SET link=FALSE WHERE prep_id=2 AND inventory_id=6");
        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toppings[?(@.toppingId==3)].sellable",contains(true)))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==1)].sellable",contains(false)));
    }

    @Test
    void hiddenItemsAreMarkedButStillLinked() throws Exception {
        // 045：show_in_sale＝FALSE 只是停售管理頁不顯示，連動照算
        jdbc.update("UPDATE inventory SET show_in_sale=FALSE WHERE inventory_id IN (1,6)");
        jdbc.update("UPDATE product SET show_in_sale=FALSE WHERE product_id=2");
        mvc.perform(get("/api/sale-status").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==1)].show",contains(false)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==3)].show",contains(true)))
            .andExpect(jsonPath("$.products[?(@.productId==2)].show",contains(false)))
            .andExpect(jsonPath("$.preps[0].show").value(true))
            .andExpect(jsonPath("$.toppings[?(@.toppingId==1)].show",contains(true)));
        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":false}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.products[?(@.productId==4)].sellable",contains(false)))
            .andExpect(jsonPath("$.products[?(@.productId==4)].stoppedBy[*]",contains("珍珠")));
    }

    @Test
    void orderWithStoppedMaterialIsRejected() throws Exception {
        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":false}]}").andExpect(status().isOk());
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM pos_order",Integer.class);
        for (var item:List.of(new Item(4L,Size.M,TempIce.LESS,Sugar.S5,1,List.of(),null),
                              new Item(3L,Size.M,TempIce.LESS,Sugar.S5,1,List.of(1L),null))) {
            var request=new Create(UUID.randomUUID(),Source.TAKE_OUT,Payment.LINE_PAY,null,null,null,null,null,null,List.of(item));
            mvc.perform(post("/api/orders").with(login("s_pos",PASSWORD)).contentType("application/json")
                .content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",containsString("珍珠 停售")));
        }
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM pos_order",Integer.class));
    }

    @Test
    void categoriesAreReturnedAndOnlyManagerCanChange() throws Exception {
        mvc.perform(get("/api/sale-status").with(login("s_pos",PASSWORD)))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==1)].category",contains("包材")))
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==8)].category",contains(nullValue())))
            .andExpect(jsonPath("$.preps[0].category").value("底料"));
        String body="{\"kind\":\"INVENTORY\",\"id\":8,\"category\":\" 水果 \"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_pos",PASSWORD)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_hq",PASSWORD)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_manager",PASSWORD)).contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inventories[?(@.inventoryId==8)].category",contains("水果")));
        // 空白＝未分類；太長、找不到
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_manager",PASSWORD)).contentType("application/json").content("{\"kind\":\"PREP\",\"id\":1,\"category\":\"  \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preps[0].category").value(nullValue()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_manager",PASSWORD)).contentType("application/json").content("{\"kind\":\"PREP\",\"id\":1,\"category\":\"一二三四五六七八九十一二三四五六七八九十一\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sale-status/categories")
            .with(login("s_manager",PASSWORD)).contentType("application/json").content("{\"kind\":\"INVENTORY\",\"id\":999,\"category\":\"水果\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void unknownIdRollsBackWholeBatch() throws Exception {
        put("s_pos","{\"inventories\":[{\"id\":6,\"onSale\":false}],\"products\":[{\"id\":999,\"onSale\":false}]}")
            .andExpect(status().isNotFound());
        assertTrue(onSale("inventory","inventory_id",6));
        put("s_pos","{}").andExpect(status().isBadRequest());
        put("s_pos","{\"toppings\":[{\"id\":1,\"onSale\":false},{\"id\":1,\"onSale\":true}]}").andExpect(status().isBadRequest());
    }
}
