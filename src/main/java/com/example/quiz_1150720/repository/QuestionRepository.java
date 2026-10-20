package com.example.quiz_1150720.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.example.quiz_1150720.entity.Question;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {
}