package com.example.drinkshop.entity;

// product 飲品與配方：配方步驟、扣料原料（JSON：原料 ID 與份數）、最低可看等級、是否上架
// minViewLevelCode 存資料庫原本的字串；不認得的值當成「沒有人能看」，避免意外公開
public record Product(long productId, String name, String category, String recipeSteps,
                      String recipeJson, String minViewLevelCode, boolean onSale) {
}
