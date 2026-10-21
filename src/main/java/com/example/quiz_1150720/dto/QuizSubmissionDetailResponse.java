package com.example.quiz_1150720.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuizSubmissionDetailResponse {

    private String userAccount;       // 填寫者帳號
    private String userName;          // 填寫者姓名
    private LocalDateTime submitTime; // 填寫時間
    private Long questionId;          // 題目 ID
    private String questionTitle;     // 題目名稱
    private String selectedOptionText;// 選擇題選項文字 (若是簡答題則為 null)
    private String answerText;        // 簡答題填寫內容 (若是選擇題則為 null)

    public QuizSubmissionDetailResponse() {
    }

    public QuizSubmissionDetailResponse(String userAccount, String userName, LocalDateTime submitTime,
            Long questionId, String questionTitle, String selectedOptionText, String answerText) {
        this.userAccount = userAccount;
        this.userName = userName;
        this.submitTime = submitTime;
        this.questionId = questionId;
        this.questionTitle = questionTitle;
        this.selectedOptionText = selectedOptionText;
        this.answerText = answerText;
    }

    public String getUserAccount() {
        return userAccount;
    }

    public void setUserAccount(String userAccount) {
        this.userAccount = userAccount;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public LocalDateTime getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(LocalDateTime submitTime) {
        this.submitTime = submitTime;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getQuestionTitle() {
        return questionTitle;
    }

    public void setQuestionTitle(String questionTitle) {
        this.questionTitle = questionTitle;
    }

    public String getSelectedOptionText() {
        return selectedOptionText;
    }

    public void setSelectedOptionText(String selectedOptionText) {
        this.selectedOptionText = selectedOptionText;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }
}