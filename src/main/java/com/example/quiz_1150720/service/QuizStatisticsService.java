package com.example.quiz_1150720.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quiz_1150720.dto.QuizOptionStatisticsResponse;
import com.example.quiz_1150720.dto.QuizSubmissionDetailResponse;
import com.example.quiz_1150720.repository.QuizSubmissionRepository;

@Service
public class QuizStatisticsService {

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(Long quizId) {
        return Map.of("totalResponses", quizSubmissionRepository.countByQuizId(quizId),
            "questions", quizSubmissionRepository.getQuestionRespondentCounts(quizId));
    }

    private final QuizSubmissionRepository quizSubmissionRepository;

    public QuizStatisticsService(QuizSubmissionRepository quizSubmissionRepository) {
        this.quizSubmissionRepository = quizSubmissionRepository;
    }

    /**
     * 1. 取得某問卷的所有填寫明細
     */
    public List<QuizSubmissionDetailResponse> getQuizSubmissions(Long quizId) {
        List<Map<String, Object>> results = quizSubmissionRepository.findQuizSubmissionsByQuizId(quizId);
        return results.stream().map(row -> {
            QuizSubmissionDetailResponse dto = new QuizSubmissionDetailResponse();
            dto.setUserAccount((String) row.get("userAccount"));
            dto.setUserName((String) row.get("userName"));

            // 💎 修復：相容 LocalDateTime 與 Timestamp，避免 ClassCastException
            Object timeObj = row.get("submitTime");
            if (timeObj instanceof LocalDateTime) {
                dto.setSubmitTime((LocalDateTime) timeObj);
            } else if (timeObj instanceof Timestamp) {
                dto.setSubmitTime(((Timestamp) timeObj).toLocalDateTime());
            }

            if (row.get("questionId") != null) {
                dto.setQuestionId(((Number) row.get("questionId")).longValue());
            }
            dto.setQuestionTitle((String) row.get("questionTitle"));
            dto.setSelectedOptionText((String) row.get("selectedOptionText"));
            dto.setAnswerText((String) row.get("answerText"));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 2. 取得某問卷各題選項統計次數
     */
    public List<QuizOptionStatisticsResponse> getQuizStatistics(Long quizId) {
        List<Map<String, Object>> results = quizSubmissionRepository.getQuizOptionStatistics(quizId);
        return results.stream().map(row -> {
            QuizOptionStatisticsResponse dto = new QuizOptionStatisticsResponse();
            if (row.get("questionId") != null) {
                dto.setQuestionId(((Number) row.get("questionId")).longValue());
            }
            dto.setQuestionTitle((String) row.get("questionTitle"));
            if (row.get("optionId") != null) {
                dto.setOptionId(((Number) row.get("optionId")).longValue());
            }
            dto.setOptionText((String) row.get("optionText"));
            if (row.get("selectCount") != null) {
                dto.setSelectCount(((Number) row.get("selectCount")).longValue());
            }
            return dto;
        }).collect(Collectors.toList());
    }
}
