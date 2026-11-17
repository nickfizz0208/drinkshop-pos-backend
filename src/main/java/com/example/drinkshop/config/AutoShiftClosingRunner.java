package com.example.drinkshop.config;

import com.example.drinkshop.service.DailyShiftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 伺服器啟動時自動檢查逾期未關班的歷史營業日（超過午夜 12 點未收班）：
 * 1. 強制將未打卡下班的員工記錄為 23:59:59 下班
 * 2. 自動結算並關閉該營業日的班次，避免隔天無法開班
 */
@Component
@Order(5)
public class AutoShiftClosingRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AutoShiftClosingRunner.class);

    private final DailyShiftService dailyShiftService;
    private final Clock clock;

    public AutoShiftClosingRunner(DailyShiftService dailyShiftService, Clock clock) {
        this.dailyShiftService = dailyShiftService;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        try {
            LocalDate today = LocalDate.now(clock);
            int closed = dailyShiftService.closeOverdueShifts(today);
            if (closed > 0) {
                log.info("【系統自動收班】已成功自動關閉 {} 個逾期未關閉的歷史營業日班次", closed);
            }
        } catch (Exception ex) {
            log.error("【系統自動收班】檢查逾期未關班次時發生錯誤: {}", ex.getMessage(), ex);
        }
    }
}
