package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 발화를 담는 녹화가 최종 실패해 전사할 원본 자체가 없다(S15P11A105-247).
 *
 * <p><b>재시도하지 않는다.</b> 실패한 Egress 는 다시 시도되지 않으므로 그 구간의 오디오는 존재하지 않는다. 기다려도 오지 않는 것을 기다리면 8시간 예산만 태운다.
 *
 * <p>전사를 계속 진행하지 않는 이유는 남은 트랙만으로 만든 문서가 {@code partial=false} 로 저장되기 때문이다. 하류는 그것을 완전한 전사로 읽고, 녹화가 실패한 학생은 한 마디도 하지 않은
 * 것으로 집계된다.
 *
 * <p>화면 공유 Egress 실패는 이 예외를 만들지 않는다. 그쪽이 실패해도 발화는 마이크 트랙에 그대로 있다.
 */
public class SessionRecordingBrokenException extends BusinessException {

    public SessionRecordingBrokenException() {
        super(PostClassTranscriptionErrorCode.SESSION_RECORDING_BROKEN);
    }
}
