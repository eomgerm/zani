package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 서버가 해석할 수 없는 검출기 계약 버전이다. 다른 기준으로 낸 판정을 같은 집계에 섞으면 안 된다. */
public class UnsupportedDetectorContractException extends BusinessException {

    public UnsupportedDetectorContractException() {
        super(AttentionEventErrorCode.UNSUPPORTED_DETECTOR_CONTRACT);
    }
}
