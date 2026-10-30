package com.example.drinkshop.service;

import com.example.drinkshop.dto.MemberRequests.*;
import com.example.drinkshop.entity.Member;
import com.example.drinkshop.exception.ApiException;
import com.example.drinkshop.repository.MemberRepository;
import com.example.drinkshop.repository.PosRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.example.drinkshop.dto.MemberRequests.PHONE_PATTERN;

@Service
public class MemberService {
    private final MemberRepository members;
    private final PosRepository pos;
    private final OrderService orders;
    private final EntityManager em;

    public MemberService(MemberRepository members, PosRepository pos, OrderService orders, EntityManager em) {
        this.members = members; this.pos = pos; this.orders = orders; this.em = em;
    }

    @Transactional(readOnly = true)
    public List<Response> search(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        var found = k.isEmpty() ? members.findTop50ByOrderByCreatedAtDesc()
            : members.findTop50ByPhoneContainingOrNameContainingOrderByPhoneAsc(k, k);
        return found.stream().map(Response::from).toList();
    }

    @Transactional(readOnly = true)
    public Response get(String phone) { return Response.from(find(phone)); }

    @Transactional
    public Response create(Create request, String username) {
        orders.actor(username);
        String phone = request.phone().trim();
        if (members.existsById(phone)) throw ApiException.conflict("此電話已經是會員");
        checkLocation(request.lat(), request.lng());
        var member = new Member(phone, request.name().trim());
        apply(member, request.address(), request.lat(), request.lng(), request.note());
        em.persist(member);
        em.flush();
        em.refresh(member); // 取回資料庫自動填入的 created_at / updated_at
        return Response.from(member);
    }

    @Transactional
    public Response update(String phone, Update request, String username) {
        orders.actor(username);
        var member = find(phone);
        checkLocation(request.lat(), request.lng());
        member.setName(request.name().trim());
        apply(member, request.address(), request.lat(), request.lng(), request.note());
        em.flush();
        em.refresh(member);
        return Response.from(member);
    }

    @Transactional
    public void delete(String phone, String username) {
        var staff = orders.actor(username);
        if (!Set.of("MANAGER", "HQ").contains(staff.level()))
            throw new ApiException(HttpStatus.FORBIDDEN, "只有店長或總部可以刪除會員");
        var member = find(phone);
        long orderCount = PosRepository.number(
            pos.one("SELECT COUNT(*) AS c FROM pos_order WHERE member_phone=?", member.getPhone()), "c");
        if (orderCount > 0) throw ApiException.conflict("此會員已有訂單紀錄，不能刪除");
        members.delete(member);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> orders(String phone) {
        var member = find(phone);
        return pos.rows("""
            SELECT order_id,order_no,business_date,source,payment,payment_status,paid_amount,refunded_amount,total,status,created_at
            FROM pos_order WHERE member_phone=? ORDER BY order_id DESC LIMIT 50
            """, member.getPhone());
    }

    private Member find(String phone) {
        String p = phone == null ? "" : phone.trim();
        if (!p.matches(PHONE_PATTERN)) throw ApiException.bad("電話格式不正確");
        return members.findById(p).orElseThrow(() -> ApiException.missing("找不到此會員"));
    }

    private static void checkLocation(BigDecimal lat, BigDecimal lng) {
        if ((lat == null) != (lng == null)) throw ApiException.bad("經緯度要同時填寫或同時留空");
    }

    private static void apply(Member member, String address, BigDecimal lat, BigDecimal lng, String note) {
        member.setAddress(blankToNull(address));
        member.setLat(lat);
        member.setLng(lng);
        member.setNote(blankToNull(note));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
