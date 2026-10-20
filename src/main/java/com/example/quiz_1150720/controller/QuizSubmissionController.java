package com.example.quiz_1150720.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.quiz_1150720.constant.QuizResponseMessage;
import com.example.quiz_1150720.dto.ApiResponse;
import com.example.quiz_1150720.dto.QuizSubmitRequest;
import com.example.quiz_1150720.service.QuizSubmissionService;
import com.example.quiz_1150720.util.JwtUtil;
import jakarta.validation.Valid;

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

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Void>> submitQuiz(
            @RequestHeader("Authorization") String tokenHeader,
            @Valid @RequestBody QuizSubmitRequest request) {

        // 終極容錯處理：自動把各種格式的 Bearer 前綴與空白濾乾淨
        String cleanToken = tokenHeader.trim();
        if (cleanToken.toLowerCase().startsWith("bearer ")) {
            cleanToken = cleanToken.substring(7).trim();
        }

        String account = jwtUtil.extractAccount(cleanToken);

        quizSubmissionService.submitQuiz(request, account);

        return ResponseEntity.ok(ApiResponse.success(QuizResponseMessage.SUBMIT_QUIZ_SUCCESS, null));
    }
}