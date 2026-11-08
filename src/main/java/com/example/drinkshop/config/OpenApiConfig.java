package com.example.drinkshop.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI posOpenApi() {
        return new OpenAPI()
            .info(new Info().title("手搖飲 POS API").version("v1")
                .description("先用 POST /api/auth/login 取得 token，按 Authorize 貼上 token，再以 Try it out 測試 API。寫入操作會修改目前連線的資料庫。"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                    .description("POST /api/auth/login 回傳的 token")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
