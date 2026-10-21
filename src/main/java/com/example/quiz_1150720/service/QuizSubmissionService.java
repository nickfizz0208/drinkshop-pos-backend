package com.example.quiz_1150720.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quiz_1150720.dto.QuizSubmitRequest;
import com.example.quiz_1150720.dto.SubmissionResponseDto;
import com.example.quiz_1150720.repository.QuizAnswerDetailRepository;
import com.example.quiz_1150720.repository.QuizSubmissionRepository;
import com.example.quiz_1150720.repository.QuizUserRepository;
import com.example.quiz_1150720.entity.QuizSubmission;
import com.example.quiz_1150720.entity.Question;
import com.example.quiz_1150720.enums.QuestionType;
import com.example.quiz_1150720.repository.QuizRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QuizSubmissionService {

    private final QuizSubmissionRepository quizSubmissionRepository;
    private final QuizAnswerDetailRepository quizAnswerDetailRepository;
    private final QuizUserRepository quizUserRepository;
    private final QuizRepository quizRepository;
    private final Clock clock;

    public QuizSubmissionService(QuizSubmissionRepository quizSubmissionRepository,
                                 QuizAnswerDetailRepository quizAnswerDetailRepository,
                                 QuizUserRepository quizUserRepository,
                                 QuizRepository quizRepository, Clock clock) {
        this.quizSubmissionRepository = quizSubmissionRepository;
        this.quizAnswerDetailRepository = quizAnswerDetailRepository;
        this.quizUserRepository = quizUserRepository;
        this.quizRepository = quizRepository;
        this.clock = clock;
    }

    @Transactional
    public void submitQuiz(QuizSubmitRequest request, String account) {
        // 鎖定一定存在的使用者列；即使首次填寫沒有主檔，也能序列化同帳號的送出。
        if (quizUserRepository.findByAccountForUpdate(account) == null) {
            throw new IllegalArgumentException("使用者帳號不存在，請重新登入");
        }

        List<QuizSubmitRequest.AnswerItem> answers = validateAndNormalize(request);

        // 1. 🌟 先把這個帳號在這份問卷的「所有舊答案明細」與「所有舊主檔」徹底清空！
        quizAnswerDetailRepository.deleteOldDetailsByQuizIdAndAccount(request.getQuizId(), account);
        quizSubmissionRepository.deleteOldSubmissionsByQuizIdAndAccount(request.getQuizId(), account);

        // 2. 寫入最新這一次的填寫主檔
        QuizSubmission submission = new QuizSubmission(
            null, request.getQuizId(), account, LocalDateTime.now(clock));
        Long submissionId = quizSubmissionRepository.saveAndFlush(submission).getId();

        // 4. 迴圈寫入每一題的作答明細
        for (QuizSubmitRequest.AnswerItem item : answers) {

            Long optId = item.getSelectedOptionId();
            String ansText = item.getAnswerText();

            // 防呆：只要是文字簡答題，確保 optId 是乾淨的 null
            if (ansText != null && !ansText.trim().isEmpty()) {
                optId = null;
            }

            quizAnswerDetailRepository.insertAnswerDetail(
                submissionId,
                item.getQuestionId(),
                optId,
                ansText
            );
        }
    }

    private List<QuizSubmitRequest.AnswerItem> validateAndNormalize(QuizSubmitRequest request) {
        if (request.getQuizId() == null || request.getAnswers() == null) {
            throw new IllegalArgumentException("問卷與作答內容不得為空");
        }
        var quiz = quizRepository.findById(request.getQuizId())
            .orElseThrow(() -> new IllegalArgumentException("問卷不存在"));
        LocalDate today = LocalDate.now(clock);
        if (!Boolean.TRUE.equals(quiz.getIsPublished())) throw new IllegalArgumentException("此問卷尚未發布，無法填寫");
        if (today.isBefore(quiz.getStartDate())) throw new IllegalArgumentException("此問卷尚未開始，無法填寫");
        if (today.isAfter(quiz.getEndDate())) throw new IllegalArgumentException("此問卷已截止，無法填寫");

        Map<Long, Question> questions = quiz.getQuestions().stream().collect(Collectors.toMap(Question::getId, q -> q));
        Map<Long, List<QuizSubmitRequest.AnswerItem>> grouped = new HashMap<>();
        for (var item : request.getAnswers()) {
            if (item == null || !questions.containsKey(item.getQuestionId())) {
                throw new IllegalArgumentException("作答包含不屬於此問卷的題目");
            }
            Question question = questions.get(item.getQuestionId());
            var normalized = new QuizSubmitRequest.AnswerItem();
            normalized.setQuestionId(question.getId());
            if (question.getType() == QuestionType.TEXT) {
                normalized.setAnswerText(item.getAnswerText() == null ? "" : item.getAnswerText().trim());
                normalized.setSelectedOptionId(null);
            } else {
                if (item.getAnswerText() != null && !item.getAnswerText().isBlank()) {
                    throw new IllegalArgumentException("選擇題請提供選項，不可用文字代替");
                }
                Long optionId = item.getSelectedOptionId();
                if (optionId == null) continue;
                if (question.getOptions().stream().noneMatch(o -> o.getId().equals(optionId))) {
                    throw new IllegalArgumentException("選項不屬於該題目");
                }
                normalized.setSelectedOptionId(optionId);
            }
            grouped.computeIfAbsent(question.getId(), id -> new ArrayList<>()).add(normalized);
        }

        for (var entry : grouped.entrySet()) {
            Question question = questions.get(entry.getKey());
            if (question.getType() != QuestionType.MULTIPLE && entry.getValue().size() > 1) {
                throw new IllegalArgumentException("單選題與簡答題不可重複作答");
            }
            if (question.getType() == QuestionType.MULTIPLE && entry.getValue().stream()
                .map(QuizSubmitRequest.AnswerItem::getSelectedOptionId).distinct().count() != entry.getValue().size()) {
                throw new IllegalArgumentException("多選題不可重複選取相同選項");
            }
        }
        Map<Integer, Question> byNo = questions.values().stream().collect(Collectors.toMap(Question::getQuestionNo, q -> q));
        List<QuizSubmitRequest.AnswerItem> result = new ArrayList<>();
        for (Question question : questions.values()) {
            if (!isVisible(question, byNo, grouped, new HashSet<>())) continue;
            var items = grouped.getOrDefault(question.getId(), List.of());
            boolean answered = items.stream().anyMatch(a -> a.getSelectedOptionId() != null ||
                (a.getAnswerText() != null && !a.getAnswerText().isBlank()));
            if (Boolean.TRUE.equals(question.getIsRequired()) && !answered) {
                throw new IllegalArgumentException("請完成必填題：「" + question.getTitle() + "」");
            }
            result.addAll(items);
        }
        return result;
    }

    private boolean isVisible(Question question, Map<Integer, Question> byNo,
                              Map<Long, List<QuizSubmitRequest.AnswerItem>> answers, java.util.Set<Long> visited) {
        if (question.getParentQuestionNo() == null || question.getParentQuestionNo() == 0) return true;
        if (!visited.add(question.getId())) throw new IllegalArgumentException("問卷跳題條件有循環，請聯絡管理員");
        Question parent = byNo.get(question.getParentQuestionNo());
        if (parent == null || !isVisible(parent, byNo, answers, visited)) return false;
        return answers.getOrDefault(parent.getId(), List.of()).stream().anyMatch(answer ->
            parent.getOptions().stream().anyMatch(option -> option.getId().equals(answer.getSelectedOptionId()) &&
                option.getOptionNo().equals(question.getTriggerOptionNo())));
    }

    public List<SubmissionResponseDto> getSubmissionsByQuizId(Long quizId) {
        List<Map<String, Object>> submissions = quizSubmissionRepository.getAllSubmissionsByQuizId(quizId);
        List<SubmissionResponseDto> resultList = new ArrayList<>();

        for (Map<String, Object> sub : submissions) {
            SubmissionResponseDto dto = new SubmissionResponseDto();
            Long submissionId = ((Number) sub.get("submissionId")).longValue();

            dto.setSubmissionId(submissionId);
            dto.setQuizId(quizId);
            Object submittedAt = sub.get("submitTime");
            if (submittedAt instanceof LocalDateTime time) dto.setSubmitTime(time);
            else if (submittedAt instanceof Timestamp time) dto.setSubmitTime(time.toLocalDateTime());

            dto.setUserAccount((String) sub.get("account"));
            dto.setUserName((String) sub.get("name"));
            dto.setEmail((String) sub.get("email"));

            List<Map<String, Object>> details = quizAnswerDetailRepository.getDetailsBySubmissionId(submissionId);
            List<SubmissionResponseDto.AnswerDetailDto> answerList = details.stream().map(d -> {
                SubmissionResponseDto.AnswerDetailDto itemDto = new SubmissionResponseDto.AnswerDetailDto();
                itemDto.setQuestionId(((Number) d.get("questionId")).longValue());

                Object optId = d.get("selectedOptionId");
                if (optId != null) itemDto.setSelectedOptionId(((Number) optId).longValue());

                itemDto.setAnswerText((String) d.get("answerText"));
                return itemDto;
            }).collect(Collectors.toList());

            dto.setAnswers(answerList);
            resultList.add(dto);
        }

        return resultList;
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponseDto> getMySubmissions(String account) {
        return quizSubmissionRepository.findByUserAccountOrderBySubmitTimeDesc(account).stream().map(sub -> {
            SubmissionResponseDto dto = new SubmissionResponseDto();
            dto.setSubmissionId(sub.getId());
            dto.setQuizId(sub.getQuizId());
            dto.setUserAccount(account);
            dto.setSubmitTime(sub.getSubmitTime());
            dto.setAnswers(quizAnswerDetailRepository.getDetailsBySubmissionId(sub.getId()).stream().map(d -> {
                SubmissionResponseDto.AnswerDetailDto answer = new SubmissionResponseDto.AnswerDetailDto();
                answer.setQuestionId(((Number) d.get("questionId")).longValue());
                Object option = d.get("selectedOptionId");
                if (option != null) answer.setSelectedOptionId(((Number) option).longValue());
                answer.setAnswerText((String) d.get("answerText"));
                return answer;
            }).toList());
            return dto;
        }).toList();
    }
}
