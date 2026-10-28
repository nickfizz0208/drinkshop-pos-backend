package com.example.drinkshop.config;

import com.example.drinkshop.repository.PosRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Configuration
public class DemoDataConfig {
    @Bean
    @ConditionalOnProperty(name="pos.demo.enabled",havingValue="true")
    CommandLineRunner demo(PosRepository repo, PasswordEncoder encoder, Clock clock,
        PlatformTransactionManager transactions,
        @Value("${pos.demo.password}") String password) {
        return args -> {
            if (password.length()<6)
                throw new IllegalStateException("Local demo requires a password of at least 6 characters ");
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                add(repo,encoder,"staff","POS 測試店員","STAFF",password);
                add(repo,encoder,"manager","POS 測試店長","MANAGER",password);
                long cashier=repo.staff("staff").orElseThrow().id();
                if (repo.rows("SELECT business_date FROM daily_shift WHERE business_date=?",LocalDate.now(clock)).isEmpty())
                    repo.update("INSERT INTO daily_shift(business_date,status,opened_by,opened_at,petty_cash,roles_json) VALUES (?,'OPEN',?,?,2000,'[]')",
                        LocalDate.now(clock),cashier,LocalDateTime.now(clock));
            });
        };
    }
    private void add(PosRepository repo,PasswordEncoder encoder,String username,String name,String level,String password) {
        if (repo.staff(username).isEmpty()) repo.update(
            "INSERT INTO staff_account(username,password_hash,name,level,active) VALUES (?,?,?,?,TRUE)",
            username,encoder.encode(password),name,level);
        // Existing accounts are never reactivated or silently reset.
    }
}
