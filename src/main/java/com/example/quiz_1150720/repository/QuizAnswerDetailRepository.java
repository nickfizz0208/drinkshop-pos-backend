package com.example.quiz_1150720.repository;

import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.quiz_1150720.entity.QuizAnswerDetail;

@Repository
public interface QuizAnswerDetailRepository extends JpaRepository<QuizAnswerDetail, Long> {

    /**
     * 1. 寫入單筆問卷作答明細 (支援選擇題與簡答題，Native SQL)
     */
    @Modifying
    @Query(value = "INSERT INTO quiz_answer_detail (submission_id, question_id, selected_option_id, answer_text) "
            + "VALUES (:submissionId, :questionId, :optionId, :answerText)", nativeQuery = true)
    public void insertAnswerDetail(
            @Param("submissionId") Long submissionId,
            @Param("questionId") Long questionId,
            @Param("optionId") Long optionId,
            @Param("answerText") String answerText);

    /**
     * 2. 根據 submission_id 撈取該次填寫的所有作答明細
     */
    @Query(value = "SELECT question_id AS questionId, selected_option_id AS selectedOptionId, answer_text AS answerText " +
                   "FROM quiz_answer_detail " +
                   "WHERE submission_id = :submissionId", nativeQuery = true)
    public List<Map<String, Object>> getDetailsBySubmissionId(@Param("submissionId") Long submissionId);

    /**
     * 3. 🌟 [新增] 刪除該帳號在此問卷的所有舊答案明細
     */
    @Modifying
    @Query(value = "DELETE FROM quiz_answer_detail WHERE submission_id IN " +
                   "(SELECT id FROM quiz_submission WHERE quiz_id = :quizId AND user_account = :account)", nativeQuery = true)
    public void deleteOldDetailsByQuizIdAndAccount(@Param("quizId") Long quizId, @Param("account") String account);
}
