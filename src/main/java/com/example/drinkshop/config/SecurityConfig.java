package com.example.drinkshop.config;

import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.security.JwtAuthenticationFilter;
import com.example.drinkshop.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

/** 員工登入與 API 權限：POST /api/auth/login 用帳密換 JWT，之後每次請求帶 Authorization: Bearer <token>。 */
@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    // 從員工資料表載入密碼雜湊、等級與在職狀態；明文密碼由 BCrypt 比對。
    @Bean UserDetailsService users(PosRepository repo) {
        return username -> {
            var staff = repo.staff(username).orElseThrow(() -> new UsernameNotFoundException("帳號不存在"));
            return User.withUsername(staff.username()).password(staff.passwordHash())
                .roles(staff.level()).disabled(!staff.active()).build();
        };
    }
    // 登入 API 用：以員工資料表 + BCrypt 驗證帳密（只在登入時比對一次，之後靠 token）
    @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    // 允許這兩個本機 Angular 網址跨來源呼叫；CORS 不等於登入授權。
    @Bean CorsConfigurationSource cors() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:4200", "http://127.0.0.1:4200"));
        cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",cors);
        return source;
    }
    // STATELESS：後端不靠登入 Session；文件、health、登入與預檢免登入，其餘 /api 都需驗證。
    // 這版 API 由前端明確帶 Authorization，不使用 Cookie Session，CSRF 設定依此設計。
    @Bean SecurityFilterChain security(HttpSecurity http, JwtService jwt, UserDetailsService users) throws Exception {
        return http.csrf(c -> c.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new JwtAuthenticationFilter(jwt, users), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint((request,response,ex) -> {
                // 沒帶 token、token 過期或竄改、帳號已停用
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"請重新登入\"}");
            }).accessDeniedHandler((request,response,ex) -> {
                response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"沒有操作權限\"}");
            }))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                // 刪除會員：只限店長、總部（MemberService 也會再檢查一次）
                .requestMatchers(HttpMethod.DELETE, "/api/members/*").hasAnyRole("MANAGER", "HQ")
                .requestMatchers(HttpMethod.POST, "/api/wastages/order-item/*").hasRole("POS")
                // 櫃台作業只限門市 POS 共用帳號：日結、訂單、會員、外送路線、上下班打卡、目前在班。
                // 嚴格比對 POS，店長、總部用個人帳號也不行，要到 POS 機上操作。
                .requestMatchers("/api/daily-shifts", "/api/daily-shifts/**",
                    "/api/orders", "/api/orders/**",
                    "/api/members", "/api/members/**", "/api/route", "/api/geo/reverse",
                    "/api/shift-staff/clock-in", "/api/shift-staff/clock-out", "/api/shift-staff/on-duty").hasRole("POS")
                // 今日打卡狀態：所有登入帳號都能看（POS 機打卡要知道開班沒、誰在班；一般員工只看得到在班的人和自己）
                .requestMatchers(HttpMethod.GET, "/api/shift-staff/today").authenticated()
                // 盤點類型的新增、修改：只限店長、總部（查看在下面，個人帳號都可以）
                .requestMatchers(HttpMethod.POST, "/api/inventory-counts/types").hasAnyRole("MANAGER", "HQ")
                .requestMatchers(HttpMethod.PUT, "/api/inventory-counts/types/*").hasAnyRole("MANAGER", "HQ")
                // 個人帳號才能用，POS 共用帳號不行：自己的打卡紀錄（修改、刪除再由 ShiftStaffService 限店長、總部）、查看配方、原料盤點
                // 原料盤點也只限個人帳號：經手人要是員工本人
                .requestMatchers("/api/shift-staff", "/api/shift-staff/**", "/api/recipes", "/api/recipes/**",
                    "/api/inventory-counts", "/api/inventory-counts/**")
                    .hasAnyRole("TRAINEE", "STAFF", "MANAGER", "HQ")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()).build();
    }
}
