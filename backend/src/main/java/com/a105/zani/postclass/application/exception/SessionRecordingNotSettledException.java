package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 세션의 녹화가 아직 안정되지 않았다(S15P11A105-247).
 *
 * <p><b>재시도 가능한 실패다.</b> 기다리면 Egress 가 끝나고 파일 행이 생긴다.
 *
 * <p>이것을 잡지 않으면 실제 녹화가 영구히 누락된다. {@code recording_files} 행은 Egress <b>종료 webhook</b> 이 도착해야 만들어지므로, 그 전에 조회하면 목록이 비어
 * 있다.
 *
 * <pre>
 * 메모 확정 → 작업 등록 → 전사 실행 → recording_files 가 비어 있음
 *   → 빈 전사 저장(partial=false) + ANALYZING 전이
 *   → 그 뒤 Egress 종료 webhook 도착
 *   → 실제 OGG 는 다시 전사되지 않는다. 작업이 이미 다음 단계로 갔기 때문이다
 * </pre>
 *
 * <p>이름을 {@code RecordingNotReadyException} 으로 두지 않았다. {@code recording} 도메인에 같은 이름이 이미 있고 뜻이 다르다(webhook 처리 중 세션을 찾지
 * 못한 경우). 두 예외가 로그에서 같은 이름으로 보이면 원인을 가르는 데 오히려 방해가 된다.
 */
public class SessionRecordingNotSettledException extends BusinessException {

    public SessionRecordingNotSettledException() {
        super(PostClassTranscriptionErrorCode.SESSION_RECORDING_NOT_SETTLED);
    }
}
