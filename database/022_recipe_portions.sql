-- 022：配方材料份數。舊配方保留原文，份數由總部按實際用量補登。
ALTER TABLE product_recipe
  ADD COLUMN portions DECIMAL(8,2) NULL,
  ADD CONSTRAINT chk_product_recipe_portions CHECK (portions > 0);
