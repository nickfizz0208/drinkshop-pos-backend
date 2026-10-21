package com.example.quiz_1150720.dto;

import java.time.LocalDateTime;
import java.util.List;

public class SubmissionResponseDto {
    private Long submissionId;
    private Long quizId;
    private LocalDateTime submitTime;
    
    // 會員相關欄位
    private String userAccount;
    private String userName;
    private String email;
    
    private List<AnswerDetailDto> answers;

    public static class AnswerDetailDto {
        private Long questionId;
        private Long selectedOptionId;
        private String answerText;

        // Getters and Setters
        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }
        public Long getSelectedOptionId() { return selectedOptionId; }
        public void setSelectedOptionId(Long selectedOptionId) { this.selectedOptionId = selectedOptionId; }
        public String getAnswerText() { return answerText; }
        public void setAnswerText(String answerText) { this.answerText = answerText; }
    }

    // Getters and Setters
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Long getQuizId() { return quizId; }
    public void setQuizId(Long quizId) { this.quizId = quizId; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
    public String getUserAccount() { return userAccount; }
    public void setUserAccount(String userAccount) { this.userAccount = userAccount; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public List<AnswerDetailDto> getAnswers() { return answers; }
    public void setAnswers(List<AnswerDetailDto> answers) { this.answers = answers; }
}