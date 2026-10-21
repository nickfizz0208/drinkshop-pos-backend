package com.example.quiz_1150720;

import com.example.quiz_1150720.dto.QuizSubmitRequest;
import com.example.quiz_1150720.service.QuizSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:submission;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class QuizSubmissionIntegrationTest {
    @Autowired QuizSubmissionService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    String account;
    Long quizId;
    Long questionId;

    @BeforeEach
    void setUp() {
        // Scalar entity IDs do not generate these foreign keys; explicitly test with constraints.
        jdbc.execute("ALTER TABLE quiz_answer_detail ADD CONSTRAINT IF NOT EXISTS fk_test_submission "
            + "FOREIGN KEY (submission_id) REFERENCES quiz_submission(id)");
        jdbc.execute("ALTER TABLE quiz_answer_detail ADD CONSTRAINT IF NOT EXISTS fk_test_question "
            + "FOREIGN KEY (question_id) REFERENCES question(id)");
        jdbc.execute("ALTER TABLE quiz_answer_detail ADD CONSTRAINT IF NOT EXISTS fk_test_option "
            + "FOREIGN KEY (selected_option_id) REFERENCES question_option(id)");
        account = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO quiz_user(account,password,name,phone,email) VALUES (?, 'hash', 'test', '0', ?)",
            account, account + "@test.local");
        jdbc.update("INSERT INTO quiz(title,description,start_date,end_date,is_published) "
            + "VALUES (?, '', ?, ?, TRUE)", account, LocalDate.now(clock), LocalDate.now(clock));
        quizId = jdbc.queryForObject("SELECT id FROM quiz WHERE title = ?", Long.class, account);
        jdbc.update("INSERT INTO question(quiz_id,question_no,title,type,is_required) VALUES (?,1,'text','TEXT',FALSE)", quizId);
        questionId = jdbc.queryForObject("SELECT id FROM question WHERE quiz_id = ?", Long.class, quizId);
    }

    @Test
    void replacesAllLegacyDuplicatesAndClearsStaleOptionId() {
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO quiz_submission(quiz_id,user_account,submit_time) VALUES (?,?,CURRENT_TIMESTAMP)", quizId, account);
        }
        List<Long> oldIds = jdbc.queryForList("SELECT id FROM quiz_submission WHERE user_account = ?", Long.class, account);
        for (Long id : oldIds) {
            jdbc.update("INSERT INTO quiz_answer_detail(submission_id,question_id,answer_text) VALUES (?,?,'old')", id, questionId);
        }
        service.submitQuiz(request("new", questionId), account);
        service.submitQuiz(request("latest", questionId), account);
        assertEquals(1, countSubmissions());
        assertEquals("latest", jdbc.queryForObject("SELECT answer_text FROM quiz_answer_detail WHERE question_id = ?", String.class, questionId));
        assertNull(jdbc.queryForObject("SELECT selected_option_id FROM quiz_answer_detail WHERE question_id = ?", Long.class, questionId));
        for (Long id : oldIds) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer_detail WHERE submission_id = ?", Integer.class, id));
        }
    }

    @Test
    void failedNewDetailRestoresOldParentAndDetails() {
        service.submitQuiz(request("original", questionId), account);
        Long originalId = jdbc.queryForObject("SELECT id FROM quiz_submission WHERE user_account = ?", Long.class, account);
        jdbc.execute("ALTER TABLE quiz_answer_detail ADD CONSTRAINT test_reject_detail CHECK (answer_text <> 'forced-db-failure')");
        try {
            assertThrows(RuntimeException.class, () -> service.submitQuiz(request("forced-db-failure", questionId), account));
        } finally {
            jdbc.execute("ALTER TABLE quiz_answer_detail DROP CONSTRAINT test_reject_detail");
        }
        assertEquals(1, countSubmissions());
        assertEquals(originalId, jdbc.queryForObject("SELECT id FROM quiz_submission WHERE user_account = ?", Long.class, account));
        assertEquals("original", jdbc.queryForObject("SELECT answer_text FROM quiz_answer_detail WHERE submission_id = ?", String.class, originalId));
    }

    @Test
    void actualSubmissionTimeRemainsStableWhenReadingAgain() {
        service.submitQuiz(request("original", questionId), account);
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 2, 10, 11, 12);
        jdbc.update("UPDATE quiz_submission SET submit_time = ? WHERE user_account = ?", fixed, account);
        assertEquals(fixed, service.getSubmissionsByQuizId(quizId).getFirst().getSubmitTime());
        assertEquals(fixed, service.getSubmissionsByQuizId(quizId).getFirst().getSubmitTime());
        assertEquals(fixed, service.getMySubmissions(account).getFirst().getSubmitTime());
    }

    @Test
    void missingOrWhitespaceRequiredTextPreservesPreviousResponse() {
        service.submitQuiz(request("old", questionId), account);
        jdbc.update("UPDATE question SET is_required = TRUE WHERE id = ?", questionId);
        var empty = new QuizSubmitRequest();
        empty.setQuizId(quizId);
        empty.setAnswers(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(empty, account));
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(request(" \n ", questionId), account));
        assertEquals("old", jdbc.queryForObject("SELECT answer_text FROM quiz_answer_detail WHERE question_id = ?", String.class, questionId));
        assertEquals(1, countSubmissions());
    }

    @Test
    void unpublishedFutureAndExpiredQuizzesCannotReplaceAnExistingResponse() {
        service.submitQuiz(request("old", questionId), account);
        LocalDate today = LocalDate.now(clock);
        jdbc.update("UPDATE quiz SET is_published = FALSE WHERE id = ?", quizId);
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(request("draft", questionId), account));
        jdbc.update("UPDATE quiz SET is_published = TRUE, start_date = ?, end_date = ? WHERE id = ?", today.plusDays(1), today.plusDays(2), quizId);
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(request("future", questionId), account));
        jdbc.update("UPDATE quiz SET start_date = ?, end_date = ? WHERE id = ?", today.minusDays(2), today.minusDays(1), quizId);
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(request("expired", questionId), account));
        assertEquals("old", jdbc.queryForObject("SELECT answer_text FROM quiz_answer_detail WHERE question_id = ?", String.class, questionId));
    }

    @Test
    void optionalQuizCanHaveAnEmptyResponse() {
        var empty = new QuizSubmitRequest();
        empty.setQuizId(quizId);
        empty.setAnswers(List.of());
        service.submitQuiz(empty, account);
        assertEquals(1, countSubmissions());
    }

    @Test
    void rejectsQuestionsFromAnotherQuiz() {
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(request("invalid", Long.MAX_VALUE), account));
        assertEquals(0, countSubmissions());
    }

    @Test
    void choiceValidationChecksRequirednessOptionOwnershipAndDuplicates() {
        jdbc.update("UPDATE question SET type = 'SINGLE', is_required = TRUE WHERE id = ?", questionId);
        Long option = addOption(questionId, 1);
        var empty = new QuizSubmitRequest();
        empty.setQuizId(quizId);
        empty.setAnswers(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(empty, account));
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(choiceRequest(Long.MAX_VALUE), account));
        var duplicate = choiceRequest(option);
        duplicate.setAnswers(List.of(duplicate.getAnswers().getFirst(), duplicate.getAnswers().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(duplicate, account));
        jdbc.update("UPDATE question SET type = 'MULTIPLE' WHERE id = ?", questionId);
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(duplicate, account));
        Long secondOption = addOption(questionId, 2);
        var multiple = choiceRequest(option);
        multiple.setAnswers(List.of(multiple.getAnswers().getFirst(), choiceRequest(secondOption).getAnswers().getFirst()));
        service.submitQuiz(multiple, account);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer_detail WHERE question_id = ?", Integer.class, questionId));
    }

    @Test
    void hiddenRequiredChildIsSkippedButVisibleChildMustBeAnswered() {
        jdbc.update("UPDATE question SET type = 'SINGLE', is_required = TRUE WHERE id = ?", questionId);
        Long yes = addOption(questionId, 1);
        Long no = addOption(questionId, 2);
        jdbc.update("INSERT INTO question(quiz_id,question_no,title,type,is_required,parent_question_no,trigger_option_no) "
            + "VALUES (?,2,'Conditional text','TEXT',TRUE,1,1)", quizId);
        Long child = jdbc.queryForObject("SELECT id FROM question WHERE quiz_id = ? AND question_no = 2", Long.class, quizId);
        service.submitQuiz(choiceRequest(no), account);
        assertThrows(IllegalArgumentException.class, () -> service.submitQuiz(choiceRequest(yes), account));
        var visible = choiceRequest(yes);
        visible.setAnswers(List.of(visible.getAnswers().getFirst(), request("child answer", child).getAnswers().getFirst()));
        service.submitQuiz(visible, account);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer_detail WHERE submission_id IN "
            + "(SELECT id FROM quiz_submission WHERE user_account = ?)", Integer.class, account));
        service.submitQuiz(choiceRequest(no), account);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer_detail WHERE question_id = ?", Integer.class, child));
    }

    private Long addOption(Long question, int number) {
        jdbc.update("INSERT INTO question_option(question_id,option_no,option_text) VALUES (?,?,?)", question, number, "Option " + number);
        return jdbc.queryForObject("SELECT id FROM question_option WHERE question_id = ? AND option_no = ?", Long.class, question, number);
    }

    private QuizSubmitRequest choiceRequest(Long option) {
        var request = request(null, questionId);
        request.getAnswers().getFirst().setSelectedOptionId(option);
        return request;
    }

    @Test
    void concurrentFirstSubmissionsLeaveOneCompleteResponse() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("first", "second").stream().map(text -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                service.submitQuiz(request(text, questionId), account);
                return null;
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, countSubmissions());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer_detail WHERE question_id = ?", Integer.class, questionId));
    }

    private int countSubmissions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM quiz_submission WHERE quiz_id = ? AND user_account = ?", Integer.class, quizId, account);
    }

    private QuizSubmitRequest request(String text, Long question) {
        var item = new QuizSubmitRequest.AnswerItem();
        item.setQuestionId(question);
        item.setSelectedOptionId(Long.MAX_VALUE); // Simulate a stale frontend selection on a text answer.
        item.setAnswerText(text);
        var request = new QuizSubmitRequest();
        request.setQuizId(quizId);
        request.setAnswers(List.of(item));
        return request;
    }
}
