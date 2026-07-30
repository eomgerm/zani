package com.a105.zani.coach.application.port;

/**
 * LLM 이 채운 팁 자리표시자 값. (S15P11A105-204)
 *
 * <p>{@link #isUsable()} 가 false 면 <b>호출과 응답은 정상이었지만 쓸 수 있는 근거가 없다</b>는 뜻이다. 파이프라인은 이것을 {@code LOW_CONFIDENCE} 로 다뤄야
 * 하며, 포트가 빈 값을 돌려준 경우({@code TIP_GENERATION_FAILED})와 구분해야 한다. 둘을 합치면 "GMS 가 계약을 어겼다"와 "강사가 설명한 내용이 없었다"를 로그에서 가려낼 수
 * 없다.
 *
 * <p>근거 구절({@code evidence})은 이 타입에 담지 않는다. 전사에 실제로 있는 문구인지 확인하는 데만 쓰고 어댑터 안에서 버린다 — 여기 담으면 상위가 그대로 강사에게 실어 보낼 여지가 생긴다.
 *
 * @param concept {@code {핵심 개념}} 또는 {@code {핵심 내용}} 에 들어갈 텍스트. 쓸 근거가 없으면 빈 문자열이다
 * @param confidence 0.0~1.0. 하한 미달은 팁을 만들지 않는 사유다 — 강사에게 틀린 개념을 짚어주는 것이 팁을 건너뛰는 것보다 나쁘다
 */
public record TipConcept(String concept, double confidence) {

    /** 응답은 정상이었지만 쓸 근거가 없었음을 나타내는 값. 파이프라인이 {@code LOW_CONFIDENCE} 로 다룬다. */
    public static TipConcept groundless() {
        return new TipConcept("", 0);
    }

    /** 이 값으로 팁 문구를 만들 수 있는가. 신뢰도 하한 판정은 파이프라인이 별도로 한다. */
    public boolean isUsable() {
        return concept != null && !concept.isBlank();
    }
}
