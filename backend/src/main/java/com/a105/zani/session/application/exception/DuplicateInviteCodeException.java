package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 초대 코드 충돌(내부 재시도 신호). CreateSessionService가 잡아서 재시도하며, 재시도 횟수를 다 쓰면 InviteCodeGenerationFailedException으로 전환되어 밖으로
 * 나간다.
 */
public class DuplicateInviteCodeException extends BusinessException {

    public DuplicateInviteCodeException(Throwable cause) {
        super(SessionApplicationErrorCode.DUPLICATE_INVITE_CODE, cause);
    }
}
