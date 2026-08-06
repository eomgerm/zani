package com.a105.zani.report.application.getstudentreport;

import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlQuery;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlUseCase;
import com.a105.zani.report.application.exception.NotSessionStudentReportException;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 저장된 개인 리포트를 읽어 화면이 쓰는 모양으로 합친다.
 *
 * <p><b>공개 게이트는 공통 리포트의 게시다.</b> 개인 리포트 행의 {@code published_at} 이 아니라 {@code session_reports} 의 게시를 본다 — 사후 파이프라인은 공개
 * 단계에서 공통 리포트에만 시각을 찍으므로(S15P11A105-304), 개인 리포트의 컬럼을 게이트로 쓰면 분석이 정상 완주해도 학생 화면이 영구히 404 다. 강사 리포트가 같은 이유로 막혀 있었고 같은
 * 방식으로 고쳤다(S15P11A105-310).
 */
@Service
@RequiredArgsConstructor
public class GetStudentReportService implements GetStudentReportUseCase {

    /**
     * 초기 재생 위치. 지금은 항상 0 이다.
     *
     * <p>서버에는 "이 리포트를 어디서부터 보여줄지"를 정하는 개념이 아직 없다. 추천 카드는 각자 자기 {@code startSeconds} 로 이동하므로(REPORT-S-004) 이 값이 대신할 일도
     * 없다. 딥링크 진입 위치를 서버가 정하게 되면 그 일감이 이 상수를 대체한다 — 화면 계약에서 필드를 빼지 않는 것은, 뺐다가 다시 넣으면 FE 파서를 두 번 고쳐야 하기 때문이다.
     */
    private static final long DEEP_LINK_ENTRY_SECONDS = 0L;

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final StudentReportQueryPort queryPort;
    private final IssueMediaUrlUseCase issueMediaUrlUseCase;

    @Override
    @Transactional(readOnly = true)
    public GetStudentReportResult get(GetStudentReportQuery query) {
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentReportException();
        }

        // 공개 게이트는 공통 리포트의 게시다. 개인 리포트 행의 published_at 이 아니다 — 그 컬럼을 채우는
        // 코드가 없어서, 그것을 보면 분석이 완주해도 학생이 리포트를 영구히 열 수 없다(S15P11A105-310).
        if (!queryPort.sessionReportPublished(query.sessionId())) {
            throw new ReportNotReadyException();
        }

        StudentReportView view = queryPort
                .findBySessionIdAndParticipantId(query.sessionId(), participant.participantId())
                .orElseThrow(ReportNotReadyException::new);

        return new GetStudentReportResult(
                new GetStudentReportResult.Activity(
                        view.publicChatCount(), view.confusedCount(), view.missedCount(), view.questionCount()),
                view.participationSummary(),
                view.recommendations().stream()
                        .map(recommendation -> new GetStudentReportResult.Recommendation(
                                recommendation.id(),
                                recommendation.recommendationType(),
                                recommendation.title(),
                                recommendation.description(),
                                recommendation.startSeconds(),
                                recommendation.endSeconds(),
                                recommendation.priority()))
                        .toList(),
                recordingUrlOrNull(query),
                durationSeconds(participant),
                view.transcript().stream()
                        .map(segment -> new GetStudentReportResult.TranscriptSegment(
                                segment.startSeconds(), segment.endSeconds(), segment.speakerName(), segment.text()))
                        .toList(),
                DEEP_LINK_ENTRY_SECONDS);
    }

    /**
     * 녹화 주소를 발급하되, 없으면 리포트 전체를 실패시키지 않고 {@code null} 로 둔다.
     *
     * <p>리포트와 녹화는 준비되는 시점이 다르다. 분석이 끝나 리포트가 게시된 뒤에도 최종 MP4 병합은 아직일 수 있고, 그때 404 를 내면 학생은 이미 볼 수 있는 참여 요약과 복습 추천까지 못 보게
     * 된다. 그래서 "녹화 없음"은 오류가 아니라 값 없음으로 내려보낸다(REPORT-S-005 와 같은 태도다).
     *
     * <p>주소를 이 자리에서 발급하는 것은 파일이 생기는 순간 코드 변경 없이 재생이 살아나게 하려는 것이다 — 발급 유스케이스가 파일 존재를 직접 확인하므로 여기서 따로 볼 것이 없다. 참가자 판정이 한
     * 번 더 일어나지만 같은 트랜잭션 안의 읽기라 값이 갈릴 여지는 없다.
     */
    private String recordingUrlOrNull(GetStudentReportQuery query) {
        try {
            return issueMediaUrlUseCase
                    .issue(new IssueMediaUrlQuery(query.sessionId(), query.memberId()))
                    .mediaUrl();
        } catch (MediaNotReadyException notReady) {
            return null;
        }
    }

    /**
     * 수업 길이. 종료 시각을 저장하기 전에 끝난 과거 세션은 {@code endedAt} 이 없어 0 이 된다.
     *
     * <p>참여도 타임라인처럼 마지막 관측 시각으로 대신하지 않는다. 이 값이 쓰이는 곳은 플레이어 눈금이고, 녹화가 없으면(그 과거 세션들이 그렇다) 눈금을 그릴 일 자체가 없다 — 관측 시각으로 길이를
     * 지어내면 재생할 수 없는 구간이 눈금에 생긴다.
     */
    private static long durationSeconds(ResolveEndedSessionParticipantResult participant) {
        Instant startedAt = participant.startedAt();
        Instant endedAt = participant.endedAt();
        if (startedAt == null || endedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startedAt, endedAt).toSeconds());
    }
}
