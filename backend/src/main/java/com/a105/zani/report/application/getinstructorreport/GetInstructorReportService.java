package com.a105.zani.report.application.getinstructorreport;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.exception.ReportNotReadyException;
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
 *
 * <p><b>공개 게이트는 공통 리포트의 게시다.</b> 강사 리포트 행의 {@code published_at} 이 아니라 {@code session_reports} 의 게시를 본다 — 사후 파이프라인은 공개
 * 단계에서 공통 리포트에만 시각을 찍으므로(S15P11A105-304), 강사 리포트의 컬럼을 게이트로 쓰면 분석이 정상 완주해도 화면이 영구히 비어 있다. 같은 화면의 수업 클립
 * ({@code GetInstructorClipService})·수업 요약이 같은 값을 보며, 게이트가 갈리면 클립 탭은 열리는데 리포트 탭은 404 인 상태가 생긴다.
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

        // 게시 확인을 먼저 한다. 아직 공개되지 않은 세션의 점수·인사이트를 읽어 올 이유가 없다.
        if (!queryPort.sessionReportPublished(query.sessionId())) {
            throw new ReportNotReadyException();
        }

        // 공개된 세션에는 강사 리포트가 반드시 있다 — SessionReportPublishService 가 그 존재를 확인하고서야
        // 시각을 찍는다. 그래도 방어적으로 같은 예외를 낸다.
        InstructorReportView report =
                queryPort.findBySessionId(query.sessionId()).orElseThrow(ReportNotReadyException::new);

        InstructorReportCounts counts = queryPort.counts(query.sessionId());

        return new GetInstructorReportResult(
                report.overallFeedback(),
                // 질문 수만 리포트 행에서 온다. AI 가 판단해 굳혀 둔 값이라 조회 시점에 세지 않는다(V19).
                new InstructorReportStats(
                        counts.studentCount(), durationSeconds(access), report.questionCount(), counts.alertCount()),
                report.scores(),
                report.insights(),
                // 248 이 내용 타임라인을 채우기 전에는 빈 목록이다. 리포트 자체는 정상이므로 오류로 다루지 않는다.
                listSessionSections.list(new ListSessionSectionsQuery(query.sessionId())));
    }

    /**
     * 수업 길이. 세션 조회를 다시 하지 않고 권한 판정이 이미 읽어 온 시각을 쓴다.
     *
     * <p>종료 시각을 저장하기 전에 끝난 과거 세션은 0 이다. 관측에서 파생하는 방법도 있지만, 그 규칙은 집중 흐름이 소유한다 — 같은 값을 두 곳에서 다르게 만들 이유가 없다.
     */
    private static long durationSeconds(ResolveEndedSessionParticipantResult access) {
        if (access.startedAt() == null || access.endedAt() == null) {
            return 0L;
        }
        return Math.max(
                0L, Duration.between(access.startedAt(), access.endedAt()).toSeconds());
    }
}
