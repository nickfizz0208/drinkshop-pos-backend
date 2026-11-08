package com.example.drinkshop.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 讀取 Authorization: Bearer &lt;token&gt;，驗證通過就把員工設成「目前登入者」。
 * 之後 Controller 的 Principal、SecurityConfig 的 hasRole 都和以前一樣能用。
 * token 無效時不設定登入者，需要登入的 API 會由 SecurityConfig 回 401。
 * 這個類別刻意不加 @Component，只掛在 Spring Security 的過濾鏈上，避免被重複執行。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserDetailsService users;
    private final WebAuthenticationDetailsSource details = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwt, UserDetailsService users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwt.verify(header.substring(7).trim()).flatMap(this::load).ifPresent(user -> {
                var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
                auth.setDetails(details.buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    // 每次都回資料庫讀：帳號被停用或刪除，token 立刻失效；職級調整也立刻生效
    private java.util.Optional<UserDetails> load(String username) {
        try {
            UserDetails user = users.loadUserByUsername(username);
            return user.isEnabled() ? java.util.Optional.of(user) : java.util.Optional.empty();
        } catch (UsernameNotFoundException e) {
            return java.util.Optional.empty();
        }
    }
}
