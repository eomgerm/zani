package com.a105.zani.attention.domain.model.timeline;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 집중 흐름 값의 반올림 규칙.
 *
 * <p>개인·집단·내용 구간 세 계산기가 같은 자리에서 같은 방향으로 끊어야 한다. 규칙이 세 곳에 흩어지면 3.665 가 어디선 3.67, 어디선 3.66 이 된다.
 */
final class FocusLevels {

    private FocusLevels() {}

    static double roundToTwo(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
