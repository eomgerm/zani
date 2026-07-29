package com.a105.zani.attention.domain.model;

/** 연속 카운터에 가할 조작(§7.3). */
public enum RunStep {

    /** 1 올린다. */
    INCREMENT,

    /** 0으로 되돌린다. */
    RESET
}
