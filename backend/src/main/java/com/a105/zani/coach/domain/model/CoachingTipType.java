package com.a105.zani.coach.domain.model;

import java.util.Optional;

import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 강사 집단 팁 5종(기준 문서 §8). 유형은 서버가 §7.6 규칙으로 결정론적으로 고른다.
 *
 * <p>LLM 이 문구 전체를 쓰지 않는 이유: 같은 상황에서 조언이 매번 달라지면 §8 이 문구를 확정한 목적(강사가 읽고 바로 행동할 수 있는 형태)이 무너진다. LLM 은 {@code {핵심
 * 개념}}·{@code {핵심 내용}} 한 자리만 채운다.
 *
 * <p>무응답·자리비움 문구에는 자리표시자가 없어 LLM 을 호출할 필요가 없다({@link #requiresConcept()}).
 */
public enum CoachingTipType {
    /** 헷갈림이 높은 경우. */
    CONFUSED_HIGH(AttentionState.CONFUSED, "추가 설명이 필요해요", true),

    /** 놓침이 높은 경우. */
    MISSED_HIGH(AttentionState.MISSED, "내용을 다시 짚어주세요", true),

    /** 헷갈림과 놓침이 함께 높은 경우(§7.6-2). 단일 상태에 대응하지 않는다. */
    CONFUSED_AND_MISSED_HIGH(null, "수업 흐름을 점검해 주세요", true),

    /** 무응답이 높은 경우. */
    NON_RESPONSE_HIGH(AttentionState.NON_RESPONSE, "학생 반응을 확인해 주세요", false),

    /** UNMEASURABLE 비율이 가장 높은 경우. */
    UNMEASURABLE_HIGH(AttentionState.UNMEASURABLE, "학생들이 자리를 비운 것 같아요", false);

    private final AttentionState state;
    private final String title;
    private final boolean requiresConcept;

    CoachingTipType(AttentionState state, String title, boolean requiresConcept) {
        this.state = state;
        this.title = title;
        this.requiresConcept = requiresConcept;
    }

    /** 이 유형이 대응하는 유의 상태. 복합 팁은 단일 상태에 대응하지 않아 비어 있다. */
    public Optional<AttentionState> state() {
        return Optional.ofNullable(state);
    }

    public String title() {
        return title;
    }

    /** 문구에 {@code {핵심 개념}}·{@code {핵심 내용}} 자리가 있는가. false 면 LLM 을 호출하지 않는다. */
    public boolean requiresConcept() {
        return requiresConcept;
    }

    /**
     * 가장 많은 학생이 겪은 상태에 대응하는 팁 유형(§7.6-3). 복합 판정은 {@link CoachingTipTypeSelector} 가 먼저 처리한다.
     *
     * @throws IllegalArgumentException 유의 상태가 아닌 값이 들어오면. 분자에 들어갈 수 없는 값이라 팁 유형도 없다
     */
    public static CoachingTipType of(AttentionState state) {
        for (CoachingTipType type : values()) {
            if (state == type.state) {
                return type;
            }
        }
        throw new IllegalArgumentException("팁 유형이 없는 상태입니다: " + state);
    }
}
