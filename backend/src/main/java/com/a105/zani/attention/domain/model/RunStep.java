package com.a105.zani.attention.domain.model;

/** 연속 카운터 하나에 가할 조작(§4.1). */
public enum RunStep {

    /** 1 올린다. */
    INCREMENT,

    /** 0으로 되돌린다. */
    RESET,

    /** 그대로 둔다. */
    KEEP
}
