package com.a105.zani.quiz.application.getstudentquiz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.quiz.application.StudentQuizAccess;

/** 본인 퀴즈 조회. 인가·로드는 제출 쪽과 {@link StudentQuizAccess} 로 공유하고, 정답 노출 여부는 Result 가 결정한다. */
@Service
@RequiredArgsConstructor
public class GetStudentQuizService implements GetStudentQuizUseCase {

    private final StudentQuizAccess studentQuizAccess;

    @Override
    @Transactional(readOnly = true)
    public GetStudentQuizResult get(GetStudentQuizQuery query) {
        return GetStudentQuizResult.from(studentQuizAccess.loadOwnQuiz(query.sessionId(), query.memberId()));
    }
}
