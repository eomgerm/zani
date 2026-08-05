package com.a105.zani.report.application.getinstructorclip;

import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlQuery;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlUseCase;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 강사에게 수업 클립의 재생 정보(녹화·전사)를 돌려준다.
 *
 * <p><b>게이트는 공통 리포트의 게시다.</b> 녹화와 전사는 특정 역할의 리포트가 아니라 세션의 공통 산출물이라, 학생 리포트나 강사 리포트가 아니라 공통
 * 리포트({@code session_reports})의 게시를 따른다 — 수업 요약({@code GetSessionSummaryService})과 같은 게이트라서 클립 탭의 두 카드가 같은 순간에 열린다.
 *
 * <p><b>권한 판정은 다른 사후 화면과 같은 경로를 쓴다.</b> {@code ResolveEndedSessionParticipantUseCase} 가 세션 존재·종료 여부·멤버십을 한 번에 보고, 역할만
 * 여기서 본다. 학생은 자기 리포트 응답({@code GetStudentReportService})에 실린 같은 모양의 재생 정보를 받으므로 이 경로는 강사 전용이다.
 */
@Service
@RequiredArgsConstructor
public class GetInstructorClipService implements GetInstructorClipUseCase {

    /**
     * 초기 재생 위치. 지금은 항상 0 이다.
     *
     * <p>서버에는 "이 클립을 어디서부터 보여줄지"를 정하는 개념이 아직 없다. 딥링크 진입 위치를 서버가 정하게 되면 그 일감이 이 상수를 대체한다 — 화면 계약에서 필드를 빼지 않는 것은, 뺐다가 다시
     * 넣으면 FE 파서를 두 번 고쳐야 하기 때문이다({@code GetStudentReportService} 와 같은 태도다).
     */
    private static final long DEEP_LINK_ENTRY_SECONDS = 0L;

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final InstructorClipQueryPort queryPort;
    private final IssueMediaUrlUseCase issueMediaUrlUseCase;

    @Override
    @Transactional(readOnly = true)
    public GetInstructorClipResult get(GetInstructorClipQuery query) {
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }
        if (!queryPort.sessionReportPublished(query.sessionId())) {
            throw new ReportNotReadyException();
        }

        return new GetInstructorClipResult(
                recordingUrlOrNull(query),
                durationSeconds(participant),
                queryPort.transcript(query.sessionId()),
                DEEP_LINK_ENTRY_SECONDS);
    }

    /**
     * 녹화 주소를 발급하되, 없으면 클립 전체를 실패시키지 않고 {@code null} 로 둔다.
     *
     * <p>게시와 최종 MP4 병합은 준비되는 시점이 다르다. 그때 404 를 내면 강사는 이미 볼 수 있는 전사까지 못 보게 되므로 "녹화 없음"은 오류가 아니라 값 없음으로
     * 내려보낸다({@code GetStudentReportService} 와 같은 태도다).
     *
     * <p>주소를 이 자리에서 발급하는 것은 파일이 생기는 순간 코드 변경 없이 재생이 살아나게 하려는 것이다 — 발급 유스케이스가 파일 존재를 직접 확인하므로 여기서 따로 볼 것이 없다.
     */
    private String recordingUrlOrNull(GetInstructorClipQuery query) {
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
     * <p>마지막 관측 시각으로 대신하지 않는 이유는 {@code GetStudentReportService} 와 같다 — 이 값이 쓰이는 곳은 플레이어 눈금이고, 녹화가 없으면 눈금을 그릴 일 자체가 없다.
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
