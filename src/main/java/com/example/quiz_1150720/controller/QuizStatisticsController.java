package com.example.quiz_1150720.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.quiz_1150720.dto.ApiResponse;
import com.example.quiz_1150720.dto.QuizOptionStatisticsResponse;
import com.example.quiz_1150720.dto.QuizSubmissionDetailResponse;
import com.example.quiz_1150720.service.QuizStatisticsService;

@RestController
@RequestMapping("/api/quiz/statistics")
@CrossOrigin(origins = "*")
public class QuizStatisticsController {

    private final QuizStatisticsService quizStatisticsService;

    public QuizStatisticsController(QuizStatisticsService quizStatisticsService) {
        this.quizStatisticsService = quizStatisticsService;
    }

    /**
     * 1. 查看一張問卷中，有哪些人填寫過以及其填寫內容
     * -> GET http://localhost:8083/api/quiz/statistics/submissions/{quizId}
     */
    @GetMapping("/submissions/{quizId}")
    public ResponseEntity<ApiResponse<List<QuizSubmissionDetailResponse>>> getQuizSubmissions(
            @PathVariable("quizId") Long quizId) {
        List<QuizSubmissionDetailResponse> data = quizStatisticsService.getQuizSubmissions(quizId);
        return ResponseEntity.ok(ApiResponse.success("查詢問卷填寫紀錄成功", data));
    }

    /**
     * 2. 統計一張問卷中，每一題內容的每個選項之次數 (忽略簡答)
     * -> GET http://localhost:8083/api/quiz/statistics/options/{quizId}
     */
    @GetMapping("/options/{quizId}")
    public ResponseEntity<ApiResponse<List<QuizOptionStatisticsResponse>>> getQuizStatistics(
            @PathVariable("quizId") Long quizId) {
        List<QuizOptionStatisticsResponse> data = quizStatisticsService.getQuizStatistics(quizId);
        return ResponseEntity.ok(ApiResponse.success("查詢問卷選項統計成功", data));
    }
}