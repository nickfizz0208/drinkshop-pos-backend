package com.example.drinkshop.controller;

import com.example.drinkshop.dto.RecipeDtos.*;
import com.example.drinkshop.service.RecipeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

// 櫃台與個人帳號可查上架飲品配方，店長及總部可編輯步驟及材料份數。
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {
    private final RecipeService recipes;
    public RecipeController(RecipeService recipes) { this.recipes = recipes; }

    // GET /api/recipes?q=奶茶&category=奶茶
    @GetMapping
    List<RecipeSummary> list(Principal principal, @RequestParam(name = "q", required = false) String q,
                             @RequestParam(name = "category", required = false) String category, HttpServletResponse response) {
        noStore(response);
        return recipes.list(principal.getName(), q, category);
    }

    // 分類下拉選單：只列出我看得到的飲品有哪些分類
    @GetMapping("/categories")
    List<String> categories(Principal principal) { return recipes.categories(principal.getName()); }

    // GET /api/recipes/{productId}：配方存在飲品上，所以 ID 是 product_id
    @GetMapping("/{productId}")
    RecipeDetail detail(Principal principal, @PathVariable("productId") long productId, HttpServletResponse response) {
        noStore(response);
        return recipes.detail(principal.getName(), productId);
    }

    @PutMapping("/{productId}")
    RecipeDetail update(Principal principal, @PathVariable("productId") long productId,
                        @Valid @RequestBody UpdateRecipe update, HttpServletResponse response) {
        noStore(response);
        return recipes.update(principal.getName(), productId, update);
    }

    // 叫瀏覽器和中間的快取都不要把配方存下來
    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
