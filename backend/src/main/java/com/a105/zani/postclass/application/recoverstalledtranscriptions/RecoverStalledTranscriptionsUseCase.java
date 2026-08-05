package com.a105.zani.postclass.application.recoverstalledtranscriptions;

/**
 * 워커 없이 남은 전사 작업을 다시 발견되게 만든다(S15P11A105-247).
 *
 * <p><b>왜 필요한가.</b> 후보 조회는 {@code next_attempt_at} 이 없는 {@code TRANSCRIBING} 을 "실행 중" 으로 보고 제외한다. 그것이 진행 중인 세션을 매 주기마다
 * 다시 집는 것을 막는 장치인데, 프로세스가 죽으면 같은 조건이 반대로 작동한다.
 *
 * <pre>
 * 서버 종료·크래시 → TRANSCRIBING + next_attempt_at = null 로 남음
 *   → 도는 워커는 없음
 *   → 후보 조회가 영원히 담지 않음
 *   → 청크 lease 가 만료돼도 회수할 세션이 디스패치되지 않는다
 * </pre>
 *
 * <p>배포 중에도 일어난다. 오케스트레이션 실행기는 종료 시 30초를 기다리지만 GMS timeout 은 180초라, 청크 호출 도중이면 기다림이 끝나기 전에 프로세스가 내려간다.
 *
 * <p>실행권을 잃는 것이 아니라 <b>되돌리는</b> 것이므로 시도 횟수를 올리지 않는다. 크래시는 단계 실패가 아니다.
 *
 * <p><b>기동 시점에만 부른다.</b> 그때는 이 인스턴스의 워커가 없어 조건에 걸리는 행이 모두 고아다. 주기적으로 부르면 살아서 도는 세션까지 되살려 같은 세션이 겹쳐 돈다.
 */
public interface RecoverStalledTranscriptionsUseCase {

    /** @return 되살린 작업 수 */
    int recover();
}
