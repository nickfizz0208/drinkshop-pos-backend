package com.example.quiz_1150720.util;

import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@Component
public class JwtUtil {

    // 透過 JJWT 自動產生符合 HS256 安全長度的密鑰
    private final SecretKey SECRET_KEY = Jwts.SIG.HS256.key().build();

    // Token 有效時間：24 小時 (毫秒)
    private final long EXPIRATION_TIME = 86400000L;

    /**
     * 1. 根據帳號產生 JWT Token
     */
    public String generateToken(String account) {
        return Jwts.builder()
                .subject(account)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(SECRET_KEY)
                .compact();
    }

    /**
     * 2. 從 Token 中解析出帳號
     */
    public String extractAccount(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.getSubject();
    }

    /**
     * 3. 驗證 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}