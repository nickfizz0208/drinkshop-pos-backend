
package com.example.quiz_1150720.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import com.example.quiz_1150720.repository.QuizUserRepository;
import com.example.quiz_1150720.util.JwtUtil;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    // CORS 跨域設定
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Angular 前端網址
        configuration.setAllowedOrigins(
            Arrays.asList("http://localhost:4200")
        );

        // 允許的 HTTP 方法
        configuration.setAllowedMethods(
            Arrays.asList(
                "GET", "POST", "PUT",
                "DELETE", "OPTIONS", "PATCH"
            )
        );

        // 允許的請求標頭
        configuration.setAllowedHeaders(
            Arrays.asList("*")
        );

        // 若使用 Cookie 或跨域憑證，再依需求調整
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, JwtUtil jwtUtil, QuizUserRepository users) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    JwtAuthenticationFilter.writeError(response, 401, "請先登入"))
                .accessDeniedHandler((request, response, error) ->
                    JwtAuthenticationFilter.writeError(response, 403, "只有管理員可以執行此操作")))
            .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, users), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/user/login", "/api/user/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/quiz/list", "/api/quiz/detail/*",
                    "/api/quiz/statistics/options/*", "/api/quiz/statistics/summary/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/quiz/submit", "/api/user/logout").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/quiz/my-submissions").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/quiz/create", "/api/quiz/update", "/api/quiz/delete").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/quiz/statistics/submissions/*").hasRole("ADMIN")
                .anyRequest().denyAll());

        return http.build();
    }
}
