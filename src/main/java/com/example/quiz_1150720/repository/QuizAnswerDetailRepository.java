package com.example.quiz_1150720.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.quiz_1150720.entity.QuizAnswerDetail;

@Repository
public interface QuizAnswerDetailRepository extends JpaRepository<QuizAnswerDetail, Long> {

    /**
     * 寫入單筆問卷作答明細 (支援選擇題與簡答題，Native SQL)
     */
    @Modifying
    @Query(value = "INSERT INTO quiz_answer_detail (submission_id, question_id, selected_option_id, answer_text) "
            + "VALUES (:submissionId, :questionId, :optionId, :answerText)", nativeQuery = true)
    public void insertAnswerDetail(
            @Param("submissionId") Long submissionId,
            @Param("questionId") Long questionId,
            @Param("optionId") Long optionId,
            @Param("answerText") String answerText);
}