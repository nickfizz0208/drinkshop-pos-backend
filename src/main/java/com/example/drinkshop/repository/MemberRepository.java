package com.example.drinkshop.repository;

import com.example.drinkshop.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 會員資料存取（JPA）。表結構由 database/ 的 SQL 管理，不會自動改表。 */
public interface MemberRepository extends JpaRepository<Member, String> {
    /** 沒有關鍵字時：最新建立的 50 位會員。 */
    List<Member> findTop50ByOrderByCreatedAtDesc();

    /** 電話或姓名包含關鍵字（部分比對），最多 50 筆。 */
    List<Member> findTop50ByPhoneContainingOrNameContainingOrderByPhoneAsc(String phone, String name);
}
