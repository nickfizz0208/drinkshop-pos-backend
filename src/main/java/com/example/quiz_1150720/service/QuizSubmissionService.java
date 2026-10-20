package com.example.quiz_1150720.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.quiz_1150720.dto.QuizSubmitRequest;
import com.example.quiz_1150720.repository.QuizAnswerDetailRepository;
import com.example.quiz_1150720.repository.QuizSubmissionRepository;

@Service
public class QuizSubmissionService {

    private final QuizSubmissionRepository quizSubmissionRepository;
    private final QuizAnswerDetailRepository quizAnswerDetailRepository;

    public QuizSubmissionService(QuizSubmissionRepository quizSubmissionRepository,
                                 QuizAnswerDetailRepository quizAnswerDetailRepository) {
        this.quizSubmissionRepository = quizSubmissionRepository;
        this.quizAnswerDetailRepository = quizAnswerDetailRepository;
    }

    @Transactional
    public void submitQuiz(QuizSubmitRequest request, String account) {
        // 1. 寫入填寫主檔
        quizSubmissionRepository.insertSubmission(request.getQuizId(), account);

        // 2. 取得剛產生的主檔 ID (submission_id)
        Long submissionId = quizSubmissionRepository.getLastInsertId();

        // 3. 迴圈寫入每一題的作答明細
        for (QuizSubmitRequest.AnswerItem item : request.getAnswers()) {
            quizAnswerDetailRepository.insertAnswerDetail(
                submissionId,
                item.getQuestionId(),
                item.getSelectedOptionId(),
                item.getAnswerText()
            );
        }
    }
}