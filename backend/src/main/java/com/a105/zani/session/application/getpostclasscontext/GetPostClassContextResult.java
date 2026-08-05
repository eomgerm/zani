package com.a105.zani.session.application.getpostclasscontext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 사후 분석에 필요한 수업 맥락(S15P11A105-248).
 *
 * <p>{@code GetSessionCoachingContextResult} 와 비슷하지만 그것을 넓히지 않는다. 그쪽은 진행 중인 수업의 실시간 팁을 위한 조회라 종료 시각이 아직 없고, "코칭 맥락"이라는
 * 이름이 사후 분석까지 가리키게 되면 두 소비자의 요구가 한 타입에 섞인다.
 *
 * <p>수업 제목은 담지 않는다. 이 맥락의 유일한 소비자가 그것을 LLM 으로 보내지 않기로 했고, 이유는 {@code ContentAnalysisRequest} 에 적었다.
 *
 * @param startedAt 수업 시작 시각
 * @param endedAt 수업 종료 시각. 아직 끝나지 않았으면 {@code null}
 * @param participantAliases 세션 참여자 id 를 화자 별칭으로 옮기는 표(GMS 가이드 §9.2)
 */
public record GetPostClassContextResult(Instant startedAt, Instant endedAt, Map<Long, String> participantAliases) {

    public GetPostClassContextResult {
        participantAliases = participantAliases == null ? Map.of() : Map.copyOf(participantAliases);
    }

    /**
     * 수업 길이. 구간이 녹화 범위 안인지 검증하는 기준이다.
     *
     * <p>전사 마지막 세그먼트의 끝이 아니라 세션 종료 시각으로 잰다 — 수업 끝부분이 무음이면 마지막 발화 이후 구간이 통째로 범위 밖으로 잘린다.
     */
    public Duration classDuration() {
        return endedAt == null ? Duration.ZERO : Duration.between(startedAt, endedAt);
    }
}
