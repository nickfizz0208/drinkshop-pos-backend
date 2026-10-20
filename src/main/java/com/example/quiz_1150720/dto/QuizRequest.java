package com.example.quiz_1150720.dto;

import java.time.LocalDate;
import java.util.List;
import com.example.quiz_1150720.constant.QuizResponseMessage;
import com.example.quiz_1150720.enums.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuizRequest {

    private Long id;

    @NotBlank(message = QuizResponseMessage.TITLE_NOT_BLANK)
    private String title;

    @NotBlank(message = QuizResponseMessage.DESCRIPTION_NOT_BLANK)
    private String description;

    @NotNull(message = QuizResponseMessage.START_DATE_NOT_NULL)
    private LocalDate startDate;

    @NotNull(message = QuizResponseMessage.END_DATE_NOT_NULL)
    private LocalDate endDate;

    private Boolean isPublished;

    @NotEmpty(message = QuizResponseMessage.QUESTIONS_NOT_EMPTY)
    @Valid
    private List<QuestionRequest> questions;

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
    public List<QuestionRequest> getQuestions() { return questions; }
    public void setQuestions(List<QuestionRequest> questions) { this.questions = questions; }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionRequest {
        private Long id;

        @NotNull(message = QuizResponseMessage.Q_NO_NOT_NULL)
        private Integer questionNo;

        @NotBlank(message = QuizResponseMessage.Q_TITLE_NOT_BLANK)
        private String title;

        @NotNull(message = QuizResponseMessage.Q_TYPE_NOT_NULL)
        private QuestionType type;

        private Boolean isRequired;
        private List<QuestionOptionRequest> options;

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
        public List<QuestionOptionRequest> getOptions() { return options; }
        public void setOptions(List<QuestionOptionRequest> options) { this.options = options; }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionOptionRequest {
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