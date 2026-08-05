package com.a105.zani.report.application.getinstructorreport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.exception.InstructorReportNotReadyException;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 저장된 강사 리포트를 읽어 화면이 쓰는 모양으로 합친다.
 *
 * <p><b>계산하지 않는다.</b> 값은 AI 가 미리 만들어 저장한 것이고 이 서비스는 권한을 보고 옮길 뿐이다. 집중 흐름처럼 조회할 때 계산하는 것과 성격이 다르다 — 같은 입력에서 같은 결과가 나오지
 * 않으므로 저장본이 유일한 사실이다.
 *
 * <p><b>권한 판정은 집중 흐름과 같은 경로를 쓴다.</b> {@code ResolveEndedSessionParticipantUseCase} 가 세션 존재·종료 여부·멤버십을 한 번에 보고, 역할만 여기서
 * 본다. 사후 화면마다 판정을 새로 짜면 한 곳이 느슨해졌을 때 드러나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class GetInstructorReportService implements GetInstructorReportUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipant;
    private final InstructorReportQueryPort queryPort;
    private final ListSessionSectionsUseCase listSessionSections;

    @Override
    @Transactional(readOnly = true)
    public GetInstructorReportResult get(GetInstructorReportQuery query) {
        ResolveEndedSessionParticipantResult access = resolveEndedSessionParticipant.resolve(
                new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));
        if (access.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        InstructorReportRecord report = queryPort
                .findBySessionId(query.sessionId())
                .filter(found -> found.publishedAt() != null)
                .orElseThrow(InstructorReportNotReadyException::new);

        return new GetInstructorReportResult(
                report.overallFeedback(),
                report.scores(),
                report.insights(),
                report.tips(),
                // 248 이 내용 타임라인을 채우기 전에는 빈 목록이다. 리포트 자체는 정상이므로 오류로 다루지 않는다.
                listSessionSections.list(new ListSessionSectionsQuery(query.sessionId())));
    }
}
