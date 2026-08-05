package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 아직 볼 수 있는 녹화가 없다(404). 수업이 진행 중이거나 최종 MP4 병합이 끝나지 않은 경우다.
 *
 * <p>참여자에게 두 경우는 구별할 이유가 없어 하나로 묶는다. 비참여자는 여기까지 오지 못하고 403 에서 걸린다 — 세션의 존재나 진행 상태를 알려주지 않는다.
 */
public class MediaNotReadyException extends BusinessException {

    public MediaNotReadyException() {
        super(MediaAccessErrorCode.MEDIA_NOT_READY);
    }
}
