package com.example.quiz_1150720.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.quiz_1150720.entity.QuizUser;

@Repository
public interface QuizUserRepository extends JpaRepository<QuizUser, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM QuizUser u WHERE u.account = :account")
    QuizUser findByAccountForUpdate(@Param("account") String account);

    /**
     * 1. 檢查帳號是否存在
     * 使用 JPQL (不加 nativeQuery = true)，Hibernate 會自動將結果正確轉為 boolean，解決 Long 轉 Boolean 報錯
     */
    @Query("SELECT COUNT(u) > 0 FROM QuizUser u WHERE u.account = :account")
    public boolean existsByAccount(@Param("account") String account);

    /**
     * 2. 檢查 Email 是否已被註冊
     */
    @Query("SELECT COUNT(u) > 0 FROM QuizUser u WHERE u.email = :email")
    public boolean existsByEmail(@Param("email") String email);

    /**
     * 3. 根據帳號查詢使用者完整實體 (這支回傳的是 QuizUser 物件，維持 Native SQL 完全沒問題)
     */
    @Query(value = "SELECT * FROM quiz_user WHERE account = :account", nativeQuery = true)
    public QuizUser findByAccount(@Param("account") String account);
}
