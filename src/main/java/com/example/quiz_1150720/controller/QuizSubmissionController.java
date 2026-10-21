package com.example.quiz_1150720.controller;

import java.util.List;
import java.security.Principal;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.quiz_1150720.dto.ApiResponse;
import com.example.quiz_1150720.dto.QuizSubmitRequest;
import com.example.quiz_1150720.dto.SubmissionResponseDto;
import com.example.quiz_1150720.service.QuizSubmissionService;
import com.example.quiz_1150720.util.JwtUtil;

@RestController
@RequestMapping("/api/quiz")
@CrossOrigin(origins = "*")
public class QuizSubmissionController {

    private final QuizSubmissionService quizSubmissionService;
    private final JwtUtil jwtUtil;

    public QuizSubmissionController(QuizSubmissionService quizSubmissionService, JwtUtil jwtUtil) {
        this.quizSubmissionService = quizSubmissionService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 填寫送出問卷
     */
    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Void>> submitQuiz(
            @Valid @RequestBody QuizSubmitRequest request, // 🌟 這裡加上 @Valid 確保正確攔截與解析
            @RequestHeader(value = "Authorization", required = false) String tokenHeader) {

        if (tokenHeader == null || tokenHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("請先登入再填寫問卷"));
        }

        // 解析 Token
        String cleanToken = tokenHeader.trim();
        if (cleanToken.toLowerCase().startsWith("bearer ")) {
            cleanToken = cleanToken.substring(7).trim();
        }

        // 取出填寫者帳號並寫入資料庫
        String account = jwtUtil.extractAccount(cleanToken);
        quizSubmissionService.submitQuiz(request, account);

        return ResponseEntity.ok(ApiResponse.success("問卷填寫送出成功", null));
    }

    /**
     * 查詢特定問卷的所有填寫紀錄與明細
     */
    @GetMapping("/statistics/submissions/{quizId}")
    public ResponseEntity<ApiResponse<List<SubmissionResponseDto>>> getSubmissionsByQuizId(
            @PathVariable("quizId") Long quizId) {

        List<SubmissionResponseDto> submissions = quizSubmissionService.getSubmissionsByQuizId(quizId);
        return ResponseEntity.ok(ApiResponse.success("取得問卷填寫紀錄成功", submissions));
    }

    @GetMapping("/my-submissions")
    public ResponseEntity<ApiResponse<List<SubmissionResponseDto>>> getMySubmissions(Principal principal) {
        return ResponseEntity.ok(ApiResponse.success("取得個人填寫紀錄成功",
            quizSubmissionService.getMySubmissions(principal.getName())));
    }
}
