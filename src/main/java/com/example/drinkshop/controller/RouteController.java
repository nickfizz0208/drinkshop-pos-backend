package com.example.drinkshop.controller;

import com.example.drinkshop.dto.RouteDtos.*;
import com.example.drinkshop.service.HereService;
import com.example.drinkshop.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 外送路線與地址查詢；全部經後端呼叫 HERE，查詢用的 key 不會出現在前端。 */
@RestController
@RequestMapping("/api")
public class RouteController {
    private final MemberService members;
    private final HereService here;

    public RouteController(MemberService members, HereService here) { this.members = members; this.here = here; }

    /** 會員的外送路線；會員還沒有座標時，先由地址自動計算並存回（GEOCODE）。 */
    @GetMapping("/members/{phone}/route")
    DeliveryRoute memberRoute(@PathVariable("phone") String phone) { return members.route(phone); }

    /** 任意終點的路線（「只用於這張單」的臨時位置）。 */
    @PostMapping("/route")
    DeliveryRoute route(@Valid @RequestBody Point point) {
        var route = here.route(point.lat(), point.lng());
        return new DeliveryRoute(point.lat(), point.lng(), null, false, null, route.distanceM(), route.polylines());
    }

    /** 座標反查地址，調整位置時顯示用。 */
    @PostMapping("/geo/reverse")
    Address reverse(@Valid @RequestBody Point point) { return new Address(here.reverse(point.lat(), point.lng())); }
}
