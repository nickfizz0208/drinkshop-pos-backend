package com.example.drinkshop;

import com.example.drinkshop.repository.PosRepository;
import com.example.drinkshop.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * 測試用登入：取代以前的 httpBasic(帳號, 密碼)。
 * 真的跑一次帳密驗證（BCrypt、停用檢查），成功就在請求帶上 Bearer token；
 * 失敗就帶一個無效 token，讓 API 回 401，和以前帳密錯誤時的行為一樣。
 */
public final class TestLogin {
    private TestLogin() {}

    public static RequestPostProcessor login(String username, String password) {
        return request -> {
            var context = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
            String token;
            try {
                context.getBean(AuthenticationManager.class)
                    .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
                var staff = context.getBean(PosRepository.class).staff(username).orElseThrow();
                token = context.getBean(JwtService.class).issue(staff.username(), staff.level()).value();
            } catch (AuthenticationException e) {
                token = "invalid";
            }
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
