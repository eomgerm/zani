package com.a105.zani.attention.domain.model;

/** 프롬프트 응답 상태. check_prompts.status 컬럼과 같은 값이다. */
public enum CheckPromptStatus {

    /** 학생이 답했다. */
    RESPONDED,

    /** 30초가 지나 답 없이 닫혔다. */
    TIMEOUT
}
