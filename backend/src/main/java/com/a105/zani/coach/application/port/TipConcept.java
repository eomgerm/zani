package com.a105.zani.coach.application.port;

/**
 * LLM 이 채운 팁 자리표시자 값. (S15P11A105-204)
 *
 * @param concept {@code {핵심 개념}} 또는 {@code {핵심 내용}} 에 들어갈 텍스트
 * @param confidence 0.0~1.0. 이 값이 하한에 못 미치면 팁을 만들지 않는다. 강사에게 틀린 개념을 짚어주는 것이 팁을 건너뛰는 것보다 나쁘다
 */
public record TipConcept(String concept, double confidence) {

    public boolean isBlank() {
        return concept == null || concept.isBlank();
    }
}
