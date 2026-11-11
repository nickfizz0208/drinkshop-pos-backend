package com.example.drinkshop.entity;

// product 飲品：配方步驟（文字）、最低可看等級、是否上架。材料清單在 product_recipe（021）
// minViewLevelCode 存資料庫原本的字串；不認得的值當成「沒有人能看」，避免意外公開
public record Product(long productId, String name, String category, String recipeSteps,
                      String minViewLevelCode, boolean onSale) {
}
