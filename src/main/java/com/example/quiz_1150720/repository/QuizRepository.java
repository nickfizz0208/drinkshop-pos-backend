package com.example.quiz_1150720.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.quiz_1150720.entity.Quiz;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, Long> {

    @Query(value = "SELECT * FROM quiz ORDER BY id DESC", nativeQuery = true)
    List<Quiz> findAllQuizzesNative();

    @Query(value = "SELECT * FROM quiz WHERE id = :id", nativeQuery = true)
    Quiz findQuizByIdNative(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM quiz WHERE id IN (:ids)", nativeQuery = true)
    void deleteQuizzesByIdsNative(@Param("ids") List<Long> ids);
}