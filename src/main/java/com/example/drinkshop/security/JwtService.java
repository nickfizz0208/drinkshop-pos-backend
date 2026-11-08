package com.example.drinkshop.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 發出與驗證登入 token（JWT，HS256 簽章）。
 * 只用 JDK 內建的 HMAC 實作，不需要額外套件。token 內只放帳號（sub）與時間，不放密碼或職級：
 * 每次請求都會回資料庫重新讀員工資料，所以停用帳號、調整職級會立刻生效。
 */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    // 固定的標頭：驗證時要求完全相同，避免被換成 alg=none 之類的攻擊
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    public record Token(String value, Instant expiresAt) {}

    private final SecretKeySpec key;
    private final Duration posTtl;
    private final Duration staffTtl;
    private final Clock clock;
    private final ObjectMapper json;

    public JwtService(@Value("${pos.jwt.secret:}") String secret,
                      @Value("${pos.jwt.ttl-pos:PT16H}") Duration posTtl,
                      @Value("${pos.jwt.ttl-staff:PT2H}") Duration staffTtl,
                      Clock clock, ObjectMapper json) {
        byte[] bytes;
        if (secret == null || secret.isBlank()) {
            // 沒設定密鑰：每次啟動隨機產生。後端重開後舊 token 會失效，大家要重新登入。
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            log.warn("未設定 pos.jwt.secret，改用隨機密鑰；後端重新啟動後所有人需要重新登入");
        } else {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) throw new IllegalStateException("pos.jwt.secret 至少要 32 個字元");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.posTtl = posTtl;
        this.staffTtl = staffTtl;
        this.clock = clock;
        this.json = json;
    }

    /** 登入成功後發 token。POS 共用帳號可用一整個營業日，個人帳號較短，避免離開後被別人接著用。 */
    public Token issue(String username, String level) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus("POS".equals(level) ? posTtl : staffTtl);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", username);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        try {
            String payload = B64.encodeToString(json.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return new Token(signingInput + "." + B64.encodeToString(sign(signingInput)), expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("無法產生登入 token", e);
        }
    }

    /** 驗證簽章與到期時間，成功就回傳帳號；任何格式錯誤、竄改、過期都回傳 empty。 */
    public Optional<String> verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) return Optional.empty();
            JsonNode claims = json.readTree(B64D.decode(parts[1]));
            JsonNode sub = claims.get("sub");
            JsonNode exp = claims.get("exp");
            if (sub == null || !sub.isTextual() || exp == null || !exp.canConvertToLong()) return Optional.empty();
            if (clock.instant().getEpochSecond() >= exp.asLong()) return Optional.empty();
            return Optional.of(sub.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
