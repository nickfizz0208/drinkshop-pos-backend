package com.example.quiz_1150720.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuizOptionStatisticsResponse {

    private Long questionId;     // 題目 ID
    private String questionTitle;// 題目名稱
    private Long optionId;       // 選項 ID
    private String optionText;   // 選項文字
    private Long selectCount;    // 被選擇的次數

    public QuizOptionStatisticsResponse() {
    }

    public QuizOptionStatisticsResponse(Long questionId, String questionTitle, Long optionId, String optionText,
            Long selectCount) {
        this.questionId = questionId;
        this.questionTitle = questionTitle;
        this.optionId = optionId;
        this.optionText = optionText;
        this.selectCount = selectCount;
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

    public Long getOptionId() {
        return optionId;
    }

    public void setOptionId(Long optionId) {
        this.optionId = optionId;
    }

    public String getOptionText() {
        return optionText;
    }

    public void setOptionText(String optionText) {
        this.optionText = optionText;
    }

    public Long getSelectCount() {
        return selectCount;
    }

    public void setSelectCount(Long selectCount) {
        this.selectCount = selectCount;
    }
}