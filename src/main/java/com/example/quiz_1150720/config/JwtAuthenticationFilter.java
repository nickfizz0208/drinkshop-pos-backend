package com.example.quiz_1150720.config;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.example.quiz_1150720.repository.QuizUserRepository;
import com.example.quiz_1150720.util.JwtUtil;
import io.jsonwebtoken.JwtException;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final QuizUserRepository users;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, QuizUserRepository users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) ||
            ("POST".equals(request.getMethod()) &&
                ("/api/user/login".equals(request.getServletPath()) ||
                 "/api/user/register".equals(request.getServletPath())));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String account;
            try {
                if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    throw new IllegalArgumentException("Missing Bearer scheme");
                }
                account = jwtUtil.extractAccount(header.substring(7).trim());
                if (account == null || account.isBlank()) throw new IllegalArgumentException("Missing subject");
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                writeError(response, 401, "登入憑證無效或已過期，請重新登入");
                return;
            }
            var user = users.findByAccount(account);
            if (user == null) {
                writeError(response, 401, "帳號不存在，請重新登入");
                return;
            }
            // Role comes from the canonical database account, never request data or unverified claims.
            String role = "admin".equals(user.getAccount()) ? "ROLE_ADMIN" : "ROLE_USER";
            var authentication = new UsernamePasswordAuthenticationToken(user.getAccount(), null,
                List.of(new SimpleGrantedAuthority(role)));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"data\":null}");
    }
}
