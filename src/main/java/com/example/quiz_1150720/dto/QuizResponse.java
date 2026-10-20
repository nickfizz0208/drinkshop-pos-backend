package com.example.quiz_1150720.dto;

import java.time.LocalDate;
import java.util.List;
import com.example.quiz_1150720.enums.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuizResponse {
    private Long id;
    private String title;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean isPublished;
    private List<QuestionResponse> questions;

    // 手動補齊 Getter / Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public Boolean getIsPublished() { return isPublished; }
    public void setIsPublished(Boolean isPublished) { this.isPublished = isPublished; }
    public List<QuestionResponse> getQuestions() { return questions; }
    public void setQuestions(List<QuestionResponse> questions) { this.questions = questions; }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionResponse {
        private Long id;
        private Integer questionNo;
        private String title;
        private QuestionType type;
        private Boolean isRequired;
        private List<QuestionOptionResponse> options;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Integer getQuestionNo() { return questionNo; }
        public void setQuestionNo(Integer questionNo) { this.questionNo = questionNo; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public QuestionType getType() { return type; }
        public void setType(QuestionType type) { this.type = type; }
        public Boolean getIsRequired() { return isRequired; }
        public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired; }
        public List<QuestionOptionResponse> getOptions() { return options; }
        public void setOptions(List<QuestionOptionResponse> options) { this.options = options; }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionOptionResponse {
        private Long id;
        private Integer optionNo;
        private String optionText;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Integer getOptionNo() { return optionNo; }
        public void setOptionNo(Integer optionNo) { this.optionNo = optionNo; }
        public String getOptionText() { return optionText; }
        public void setOptionText(String optionText) { this.optionText = optionText; }
    }
}