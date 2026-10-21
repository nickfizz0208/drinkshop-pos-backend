package com.example.quiz_1150720;

import com.example.quiz_1150720.util.JwtUtil;
import com.example.quiz_1150720.exception.GlobalExceptionHandler;
import com.example.quiz_1150720.controller.QuizSubmissionController;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void sameConfiguredKeyAcceptsTokenAfterRecreation() {
        String token = new JwtUtil(SECRET).generateToken("user02");
        assertEquals("user02", new JwtUtil(SECRET).extractAccount(token));
    }

    @Test
    void changedKeyReturnsUnauthorizedWithoutLeakingSignatureDetails() {
        String token = new JwtUtil(SECRET).generateToken("user02");
        var differentKey = new JwtUtil("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        var error = assertThrows(JwtException.class, () -> differentKey.extractAccount(token));
        var response = new GlobalExceptionHandler().handleJwtException(error);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("登入憑證無效或已過期，請重新登入", response.getBody().getMessage());
    }

    @Test
    void missingAuthorizationReturnsUnauthorized() {
        var controller = new QuizSubmissionController(null, new JwtUtil(SECRET));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.submitQuiz(null, null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.submitQuiz(null, " ").getStatusCode());
    }
}
