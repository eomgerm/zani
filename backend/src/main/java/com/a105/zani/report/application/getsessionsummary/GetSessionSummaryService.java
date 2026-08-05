package com.a105.zani.report.application.getsessionsummary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;

/**
 * 참여자에게 공통 수업 요약을 돌려준다.
 *
 * <p>역할 분기가 없다는 것이 이 서비스의 요점이다. 강사용·학생용으로 나누면 같은 수업을 두 문장으로 설명하게 되고, 강사가 학생에게 "리포트 3번 항목" 이라고 말할 수 없게 된다.
 *
 * <p>요약이 없을 때 빈 문자열이 아니라 {@code REPORT_NOT_READY} 를 내는 이유: 요약은 사후 분석이 끝나야 생기는 값이라 "없음" 과 "빈 요약" 이 뜻이 다르다. 화면은 전자에는
 * 기다리라고 적고 후자에는 빈 카드를 그려야 하는데, 빈 문자열로 합치면 그 구분이 사라진다. 내용 구간({@code ListSessionSectionsUseCase})이 빈 목록을 정상으로 두는 것과 다른
 * 선택이며, 그쪽은 목록이라 "0개" 가 그 자체로 상태를 말한다.
 */
@Service
@RequiredArgsConstructor
public class GetSessionSummaryService implements GetSessionSummaryUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final SessionSummaryQueryPort queryPort;

    @Override
    @Transactional(readOnly = true)
    public GetSessionSummaryResult get(GetSessionSummaryQuery query) {
        // 결과를 쓰지 않고 버린다 — 부르는 목적이 값이 아니라 판정이다. 비참여자·미종료 세션은 여기서 예외로 끊긴다.
        resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));

        return queryPort
                .findPublishedSummaryBySessionId(query.sessionId())
                .map(GetSessionSummaryResult::new)
                .orElseThrow(ReportNotReadyException::new);
    }
}
