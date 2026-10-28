package com.example.drinkshop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
public class DrinkshopApplication {
    public static void main(String[] args) { SpringApplication.run(DrinkshopApplication.class, args); }
    @Bean
    Clock clock() { return Clock.system(ZoneId.of("Asia/Taipei")); }
}
