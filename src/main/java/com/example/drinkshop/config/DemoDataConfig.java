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

/**
 * 示範用啟動初始化，只有 pos.demo.enabled=true 才執行，會寫入目前設定的資料庫。
 * 補建缺少的測試員工與當日開班，不是正式開班／日結功能；隔離測試需停用。
 */
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
                // 門市 POS 機的共用帳號（需先執行 database/014_pos_level.sql）
                add(repo,encoder,"pos","門市 POS","POS",password);
                // 不再自動開班：用 pos 帳號登入後，在畫面上選擇開班人員開班
            });
        };
    }
    private void add(PosRepository repo,PasswordEncoder encoder,String username,String name,String level,String password) {
        if (repo.staff(username).isEmpty()) repo.update(
            "INSERT INTO staff_account(username,password_hash,name,level,active) VALUES (?,?,?,?,TRUE)",
            username,encoder.encode(password),name,level);
        // 已存在帳號不改密碼、不重新啟用；當日已有班表也不會重開已關閉的班。
    }
}
