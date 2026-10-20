package com.example.quiz_1150720.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuizSubmitRequest {

    @NotNull(message = "問卷 ID 不得為空")
    private Long quizId;

    @NotEmpty(message = "作答內容不得為空")
    @Valid
    private List<AnswerItem> answers;

    public Long getQuizId() {
        return quizId;
    }

    public void setQuizId(Long quizId) {
        this.quizId = quizId;
    }

    public List<AnswerItem> getAnswers() {
        return answers;
    }

    public void setAnswers(List<AnswerItem> answers) {
        this.answers = answers;
    }

    @Getter
    @Setter
    public static class AnswerItem {

        @NotNull(message = "題目 ID 不得為空")
        private Long questionId;

        // 選擇題選項 ID (若為簡答題則傳 null)
        private Long selectedOptionId;

        // 簡答題文字內容 (若為選擇題則傳 null)
        private String answerText;

        public Long getQuestionId() {
            return questionId;
        }

        public void setQuestionId(Long questionId) {
            this.questionId = questionId;
        }

        public Long getSelectedOptionId() {
            return selectedOptionId;
        }

        public void setSelectedOptionId(Long selectedOptionId) {
            this.selectedOptionId = selectedOptionId;
        }

        public String getAnswerText() {
            return answerText;
        }

        public void setAnswerText(String answerText) {
            this.answerText = answerText;
        }
    }
}