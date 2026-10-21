package com.example.quiz_1150720.config;

import com.example.quiz_1150720.entity.QuizUser;
import com.example.quiz_1150720.repository.QuizUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final QuizUserRepository quizUserRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(QuizUserRepository quizUserRepository, PasswordEncoder passwordEncoder) {
        this.quizUserRepository = quizUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        
        // 1. 檢查並建立系統管理員 (對應前端的 admin / 123456)
        if (!quizUserRepository.existsByAccount("admin")) { 
            QuizUser admin = new QuizUser();
            admin.setAccount("admin");
            // 密碼改為跟前端快速登入一致的 123456
            admin.setPassword(passwordEncoder.encode("123456")); 
            admin.setName("系統管理員");
            admin.setEmail("admin@example.com");
            admin.setPhone("0900000000");
            
            quizUserRepository.save(admin); 
            System.out.println(">>> 已自動建立預設管理員帳號: admin / 123456");
        }

        // 2. 檢查並建立一般會員 (對應前端的 user02 / 123123)
        if (!quizUserRepository.existsByAccount("user02")) {
            QuizUser user = new QuizUser();
            user.setAccount("user02");
            // 密碼改為跟前端快速登入一致的 123123
            user.setPassword(passwordEncoder.encode("123123")); 
            user.setName("一般會員02");
            user.setEmail("user02@example.com");
            user.setPhone("0912345678");
            
            quizUserRepository.save(user); 
            System.out.println(">>> 已自動建立預設一般會員帳號: user02 / 123123");
        }
    }
}