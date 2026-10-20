package com.example.quiz_1150720.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.quiz_1150720.constant.QuizResponseMessage;
import com.example.quiz_1150720.dto.UserLoginRequest;
import com.example.quiz_1150720.dto.UserLoginResponse;
import com.example.quiz_1150720.dto.UserRegisterRequest;
import com.example.quiz_1150720.entity.QuizUser;
import com.example.quiz_1150720.repository.QuizUserRepository;
import com.example.quiz_1150720.util.JwtUtil;

@Service
public class QuizUserService {

    private final QuizUserRepository quizUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public QuizUserService(QuizUserRepository quizUserRepository, 
                           PasswordEncoder passwordEncoder, 
                           JwtUtil jwtUtil) {
        this.quizUserRepository = quizUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 1. 註冊功能（使用 Argon2 雜湊密碼）
     */
    @Transactional
    public void register(UserRegisterRequest request) {
        if (quizUserRepository.existsByAccount(request.getAccount())) {
            throw new RuntimeException(QuizResponseMessage.USER_ACCOUNT_EXISTS);
        }
        if (quizUserRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException(QuizResponseMessage.USER_EMAIL_EXISTS);
        }

        QuizUser user = new QuizUser();
        user.setAccount(request.getAccount());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        quizUserRepository.save(user);
    }

    /**
     * 2. 登入功能：核對密碼並回傳包含 JWT Token 的資料
     */
    public UserLoginResponse login(UserLoginRequest request) {
        QuizUser user = quizUserRepository.findByAccount(request.getAccount());
        if (user == null) {
            throw new RuntimeException(QuizResponseMessage.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException(QuizResponseMessage.PASSWORD_MISMATCH);
        }

        // 生成 JWT Token
        String token = jwtUtil.generateToken(user.getAccount());

        // 回傳包含 Token 的登入成功回應物件
        return new UserLoginResponse(token, user.getAccount(), user.getName());
    }

    /**
     * 3. 登出功能（JWT 為無狀態，由前端移除 Token 即可）
     */
    public void logout() {
    }
}