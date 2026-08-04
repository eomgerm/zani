package com.a105.zani.recording.application.gettrackfiles;

/**
 * 세션의 녹화 산출물과 준비 상태를 읽는다.
 *
 * <p>사후 전사(S15P11A105-247)가 전사 대상을 고르기 위한 경로다. <b>고르는 규칙은 여기 두지 않는다</b> — "마이크만" 은 전사 쪽 정책이고, 최종 MP4 병합은 화면 공유도 필요하다. 이
 * 유스케이스는 있는 것을 그대로 주고, 무엇을 쓸지는 부르는 쪽이 정한다.
 *
 * <p>다만 <b>준비 상태는 함께 준다.</b> 그것은 고르는 규칙이 아니라 "이 목록을 믿어도 되는가" 이고, 판단 근거({@code recordings}·{@code recording_outbox}·세션
 * 종료 여부)가 모두 이 도메인 안에 있다. 부르는 쪽이 그 세 테이블을 직접 보게 하면 recording 의 내부 상태가 밖으로 새어 나간다.
 *
 * <p>포트를 {@code postclass} 가 정의하고 {@code recording} 이 구현하는 방식을 쓰지 않는다. 그러면 {@code recording → postclass} 의존이 생기는데
 * {@code postclass} 는 이미 {@code recording} 을 참조하고 있어(트랙 종류 enum) 두 모듈이 서로를 물게 된다. 읽기 유스케이스를 소유자 쪽에 두면 의존이
 * {@code postclass → recording} 한 방향으로 남는다 — {@code session} 과의 관계에서 쓰는 방식과 같다.
 */
public interface GetSessionRecordingSnapshotUseCase {

    /** @return 파일 id 오름차순 목록과 준비 상태. 파일이 없으면 빈 목록이며, 그 뜻은 준비 상태가 정한다 */
    SessionRecordingSnapshot findBySessionId(Long sessionId);
}
