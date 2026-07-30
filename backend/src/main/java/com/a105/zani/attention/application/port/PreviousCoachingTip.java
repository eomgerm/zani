package com.a105.zani.attention.application.port;

import java.time.Instant;

/**
 * 직전에 강사에게 보여준 팁.
 *
 * <p>팁 생성(티켓 204)이 같은 유형을 연달아 내지 않도록 유형과 시각을 함께 넘긴다. 시각만 넘기면 "방금 헷갈림 팁을 봤다"는 사실을 알 수 없어 같은 조언이 반복된다.
 */
public record PreviousCoachingTip(CoachingTipType tipType, Instant deliveredAt) {}
