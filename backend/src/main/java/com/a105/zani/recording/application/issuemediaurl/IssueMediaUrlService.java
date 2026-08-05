package com.a105.zani.recording.application.issuemediaurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;

/**
 * 세션 참여자에게 녹화 접근 주소를 발급한다(ACCESS-001, NFR-SEC-002).
 *
 * <p>역할은 보지 않는다. 공통 녹화·전사는 그 수업에 실제로 참여한 사람이면 강사·학생 모두 열람한다(FRD §21). 막아야 할 것은 비참여자이고, 그 판정은
 * {@link ResolveEndedSessionParticipantUseCase} 가 멤버십 → 세션 존재 → 종료 순서로 내린다 — 비멤버에게 세션의 존재나 상태를 알리지 않는 순서다.
 *
 * <p>발급 전에 파일이 실제로 있는지 확인한다. 없는 주소를 내주면 클라이언트는 재생을 시작한 뒤에야 실패를 알게 되고, 그 실패는 만료와 구분되지 않아 재발급 루프로 들어간다.
 *
 * <p>상태를 바꾸지 않는다 — 서명은 계산일 뿐 저장하지 않는다. 트랜잭션은 참가자 조회 쪽이 자기 것으로 갖는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueMediaUrlService implements IssueMediaUrlUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final LectureMediaPort lectureMediaPort;
    private final MediaAccessPort mediaAccessPort;

    @Override
    public IssueMediaUrlResult issue(IssueMediaUrlQuery query) {
        try {
            resolveEndedSessionParticipantUseCase.resolve(
                    new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));
        } catch (SessionNotEndedException stillLive) {
            // 진행 중 수업의 녹화는 아직 만들어지지 않았다. 참여자에게는 충돌(409)이 아니라 "아직 준비 안 됨"(404)이다.
            throw new MediaNotReadyException();
        }

        if (lectureMediaPort.findLectureRecording(query.sessionId()).isEmpty()) {
            throw new MediaNotReadyException();
        }

        IssuedMediaUrl issued = mediaAccessPort.issue(query.sessionId());
        // 감사 로그(S15P11A105-119)의 audit_logs 테이블이 아직 없다. 그때까지 접근 사실만 운영 로그에 남긴다 —
        // 주소와 토큰은 남기지 않는다(로그가 곧 유효한 자격이 된다).
        log.info("media access issued: sessionId={}, memberId={}", query.sessionId(), query.memberId());
        return new IssueMediaUrlResult(issued.url(), issued.expiresAt());
    }
}
