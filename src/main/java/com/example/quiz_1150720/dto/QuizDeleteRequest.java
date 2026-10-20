package com.example.quiz_1150720.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuizDeleteRequest {

    @NotEmpty(message = "刪除的問卷 ID 清單不得為空")
    private List<Long> ids;

    public QuizDeleteRequest() {
    }

    public QuizDeleteRequest(List<Long> ids) {
        this.ids = ids;
    }

    // 手寫 Getter / Setter（讓 QuizService 抓得到 getIds()）
    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }
}