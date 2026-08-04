package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 영구 실패한 청크가 있어 완전한 전사를 만들 수 없다(S15P11A105-247).
 *
 * <p><b>재시도하지 않는다.</b> 재시도 상한을 넘었거나 재시도 불가 사유로 끝난 청크이므로 다시 불러도 같은 결과다. 파이프라인 전체를 실패로 종료한다. 아직 처리 중인 청크가 남은 경우는 이 예외가
 * 아니라 {@link TranscriptNotReadyException} 이다.
 *
 * <p>MVP 정책은 불완전한 전사를 다음 단계로 넘기지 않는 것이다. 부분 전사를 넘기려면 하류 작업이 "이건 일부만 있다" 를 알고 다르게 처리하는 계약이 있어야 하는데, 그 계약이 아직 없다. 없는 상태에서
 * 넘기면 하류는 완전한 전사로 착각하고, 결과 리포트에서 특정 학생의 발화가 통째로 빠진 채 "참여도 낮음" 으로 집계된다 — 조용히 틀린 결론이 나가는 쪽이 실패보다 나쁘다.
 */
public class TranscriptIncompleteException extends BusinessException {

    public TranscriptIncompleteException() {
        super(PostClassTranscriptionErrorCode.TRANSCRIPT_INCOMPLETE);
    }
}
