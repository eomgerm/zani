package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 청크 결과를 수업 타임라인 위에 놓을 수 없다(S15P11A105-247).
 *
 * <p>절대 시각은 <b>파일 시작 오프셋 + 청크 시작 오프셋 + 세그먼트 상대 시각</b> 세 겹의 합이다. 어느 한 겹이 없거나 어긋나면 남은 두 겹으로 그럴듯한 숫자가 만들어지고, 그 숫자는 검증할 방법이
 * 없다. 그래서 계산이 아니라 조립 자체를 멈춘다.
 *
 * <p>재시도하지 않는다. 같은 입력으로 다시 조립해도 같은 결과다. 원인은 데이터 쪽에 있다 — 화자를 모르는 파일 행(V12 이전 legacy), 시각이 없는 파일 행, 청크 길이를 넘는 세그먼트. 어느
 * 쪽인지는 사람이 봐야 한다.
 */
public class TranscriptAssemblyInvalidException extends BusinessException {

    public TranscriptAssemblyInvalidException() {
        super(PostClassTranscriptionErrorCode.TRANSCRIPT_ASSEMBLY_INVALID);
    }
}
