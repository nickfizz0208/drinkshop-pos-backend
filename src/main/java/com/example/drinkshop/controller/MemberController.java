package com.example.drinkshop.controller;

import com.example.drinkshop.dto.MemberRequests.*;
import com.example.drinkshop.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MemberService members;
    public MemberController(MemberService members) { this.members = members; }

    @GetMapping
    List<Response> search(@RequestParam(name = "keyword", required = false) String keyword) { return members.search(keyword); }

    @GetMapping("/{phone}")
    Response get(@PathVariable("phone") String phone) { return members.get(phone); }

    @PostMapping
    Response create(@Valid @RequestBody Create request, Principal principal) {
        return members.create(request, principal.getName());
    }

    @PutMapping("/{phone}")
    Response update(@PathVariable("phone") String phone, @Valid @RequestBody Update request, Principal principal) {
        return members.update(phone, request, principal.getName());
    }

    @DeleteMapping("/{phone}")
    Map<String, Object> delete(@PathVariable("phone") String phone, Principal principal) {
        members.delete(phone, principal.getName());
        return Map.of("success", true);
    }

    @GetMapping("/{phone}/orders")
    List<Map<String, Object>> orders(@PathVariable("phone") String phone) { return members.orders(phone); }
}
