package com.a105.zani.attention.application.port;

/**
 * 트리거는 열렸지만 팁을 보여주지 못한 사유 4종.
 *
 * <p>전사 실패와 문구 생성 실패를 합치면 "GMS 가 느리다"와 "우리 프롬프트가 틀렸다"를 구분할 수 없어 고칠 방향이 안 나온다. 서버가 팁 유형을 결정론적으로 고르므로 "조언할 것이 없다"는 사유는
 * 없다.
 *
 * <p>이 값이 있다고 코칭 기능이 죽은 것은 아니다. 다음 트리거에서 복구되므로 강사 화면의 비활성 표시(티켓 76)는 폴링 자체가 연속 실패할 때만 쓴다.
 */
public enum CoachingTipUnavailableReason {
    /** 강사 오디오 버퍼가 최소 길이에 못 미쳐 전사할 것이 없었다. */
    NO_TRANSCRIPT,

    /** 전사 호출이 실패했다(타임아웃·크레딧 소진 포함). */
    TRANSCRIPTION_FAILED,

    /** 팁 문구 생성이 실패했다(응답 스키마 검증 실패 포함). */
    TIP_GENERATION_FAILED,

    /** 생성된 팁의 신뢰도가 표시 기준에 못 미쳤다. */
    LOW_CONFIDENCE
}
