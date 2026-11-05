package com.example.drinkshop.controller;

import com.example.drinkshop.repository.PosRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 員工清單：排班、打卡選人用。只回傳在職員工的編號、姓名、職級，不含密碼等欄位；POS 共用帳號不是真人，不列出。 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {
    private final PosRepository pos;
    public StaffController(PosRepository pos) { this.pos = pos; }

    public record StaffOption(long staffId, String name, String level) {}

    /** GET /api/staff */
    @GetMapping
    List<StaffOption> list() {
        return pos.rows("SELECT staff_id, name, level FROM staff_account WHERE active = TRUE AND level <> 'POS' ORDER BY staff_id")
            .stream()
            .map(r -> new StaffOption(PosRepository.number(r, "staff_id"), (String) r.get("name"), (String) r.get("level")))
            .toList();
    }
}
