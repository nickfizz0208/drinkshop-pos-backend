package com.example.quiz_1150720.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.quiz_1150720.constant.QuizResponseMessage;
import com.example.quiz_1150720.dto.QuizDeleteRequest;
import com.example.quiz_1150720.dto.QuizRequest;
import com.example.quiz_1150720.dto.QuizResponse;
import com.example.quiz_1150720.entity.Question;
import com.example.quiz_1150720.entity.QuestionOption;
import com.example.quiz_1150720.entity.Quiz;
import com.example.quiz_1150720.enums.QuestionType;
import com.example.quiz_1150720.repository.QuizRepository;

@Service
public class QuizService {

    private final QuizRepository quizRepository;

    public QuizService(QuizRepository quizRepository) {
        this.quizRepository = quizRepository;
    }

    /**
     * 1. 創建問卷
     */
    @Transactional
    public QuizResponse createQuiz(QuizRequest request) {
        validateQuizLogic(request);
        Quiz quiz = convertRequestToEntity(request);
        Quiz savedQuiz = quizRepository.save(quiz);
        return convertEntityToResponse(savedQuiz);
    }

    /**
     * 2. 更新問卷
     */
    @Transactional
    public QuizResponse updateQuiz(QuizRequest request) {
        if (request.getId() == null || !quizRepository.existsById(request.getId())) {
            throw new RuntimeException(QuizResponseMessage.QUIZ_NOT_FOUND_FOR_UPDATE);
        }

        validateQuizLogic(request);
        Quiz quiz = convertRequestToEntity(request);
        Quiz updatedQuiz = quizRepository.save(quiz);
        return convertEntityToResponse(updatedQuiz);
    }

    /**
     * 3. 取得所有問卷列表（Native SQL 排序）
     */
    public List<QuizResponse> getAllQuizzes() {
        List<Quiz> quizzes = quizRepository.findAllQuizzesNative();
        return quizzes.stream()
                .map(this::convertEntityToResponse)
                .collect(Collectors.toList());
    }

    /**
     * 4. 根據問卷 ID 取得單筆問卷詳情（Native SQL）
     */
    public QuizResponse getQuizById(Long id) {
        Quiz quiz = quizRepository.findQuizByIdNative(id);
        if (quiz == null) {
            throw new RuntimeException(QuizResponseMessage.QUIZ_NOT_FOUND);
        }
        return convertEntityToResponse(quiz);
    }

    /**
     * 5. 批次刪除問卷
     */
    @Transactional
    public void deleteQuizzes(QuizDeleteRequest request) {
        List<Long> ids = request.getIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        quizRepository.deleteQuizzesByIdsNative(ids);
    }

    /**
     * 業務驗證邏輯
     */
    private void validateQuizLogic(QuizRequest request) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new RuntimeException(QuizResponseMessage.END_DATE_BEFORE_START_DATE);
        }

        if (request.getQuestions() == null) return;

        for (QuizRequest.QuestionRequest qReq : request.getQuestions()) {
            QuestionType type = qReq.getType();
            if (type == QuestionType.SINGLE || type == QuestionType.MULTIPLE) {
                if (qReq.getOptions() == null || qReq.getOptions().isEmpty()) {
                    throw new RuntimeException(String.format(QuizResponseMessage.OPTION_REQUIRED_FOR_CHOICE, qReq.getTitle()));
                }
                for (QuizRequest.QuestionOptionRequest oReq : qReq.getOptions()) {
                    if (oReq.getOptionNo() == null) {
                        throw new RuntimeException(String.format(QuizResponseMessage.OPTION_NO_NOT_NULL_MSG, qReq.getTitle()));
                    }
                    if (!StringUtils.hasText(oReq.getOptionText())) {
                        throw new RuntimeException(String.format(QuizResponseMessage.OPTION_TEXT_NOT_BLANK_MSG, qReq.getTitle()));
                    }
                }
            } else if (type == QuestionType.TEXT) {
                if (qReq.getOptions() != null && !qReq.getOptions().isEmpty()) {
                    throw new RuntimeException(String.format(QuizResponseMessage.OPTION_NOT_ALLOWED_FOR_TEXT, qReq.getTitle()));
                }
            }
        }
    }

    /**
     * Request DTO 轉 Entity
     */
    private Quiz convertRequestToEntity(QuizRequest request) {
        Quiz quiz = new Quiz();
        quiz.setId(request.getId());
        quiz.setTitle(request.getTitle());
        quiz.setDescription(request.getDescription());
        quiz.setStartDate(request.getStartDate());
        quiz.setEndDate(request.getEndDate());
        quiz.setIsPublished(request.getIsPublished());

        if (request.getQuestions() != null) {
            List<Question> questions = request.getQuestions().stream().map(qReq -> {
                Question question = new Question();
                question.setId(qReq.getId());
                question.setQuestionNo(qReq.getQuestionNo());
                question.setTitle(qReq.getTitle());
                question.setType(qReq.getType());
                question.setIsRequired(qReq.getIsRequired());
                question.setQuiz(quiz);

                if (qReq.getOptions() != null) {
                    List<QuestionOption> options = qReq.getOptions().stream().map(oReq -> {
                        QuestionOption option = new QuestionOption();
                        option.setId(oReq.getId());
                        option.setOptionNo(oReq.getOptionNo());
                        option.setOptionText(oReq.getOptionText());
                        option.setQuestion(question);
                        return option;
                    }).collect(Collectors.toList());
                    question.setOptions(options);
                }
                return question;
            }).collect(Collectors.toList());
            quiz.setQuestions(questions);
        }
        return quiz;
    }

    /**
     * Entity 轉 Response DTO
     */
    private QuizResponse convertEntityToResponse(Quiz quiz) {
        QuizResponse response = new QuizResponse();
        response.setId(quiz.getId());
        response.setTitle(quiz.getTitle());
        response.setDescription(quiz.getDescription());
        response.setStartDate(quiz.getStartDate());
        response.setEndDate(quiz.getEndDate());
        response.setIsPublished(quiz.getIsPublished());

        if (quiz.getQuestions() != null) {
            List<QuizResponse.QuestionResponse> qResponses = quiz.getQuestions().stream().map(q -> {
                QuizResponse.QuestionResponse qRes = new QuizResponse.QuestionResponse();
                qRes.setId(q.getId());
                qRes.setQuestionNo(q.getQuestionNo());
                qRes.setTitle(q.getTitle());
                qRes.setType(q.getType());
                qRes.setIsRequired(q.getIsRequired());

                if (q.getOptions() != null) {
                    List<QuizResponse.QuestionOptionResponse> oResponses = q.getOptions().stream().map(o -> {
                        QuizResponse.QuestionOptionResponse oRes = new QuizResponse.QuestionOptionResponse();
                        oRes.setId(o.getId());
                        oRes.setOptionNo(o.getOptionNo());
                        oRes.setOptionText(o.getOptionText());
                        return oRes;
                    }).collect(Collectors.toList());
                    qRes.setOptions(oResponses);
                }
                return qRes;
            }).collect(Collectors.toList());
            response.setQuestions(qResponses);
        }
        return response;
    }
}