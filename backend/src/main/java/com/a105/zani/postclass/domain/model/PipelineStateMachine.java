package com.a105.zani.postclass.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사후 처리 작업의 단계 전이 규칙(FRD §17.1 처리 순서).
 *
 * <p>단계는 {@code QUEUED → TRANSCRIBING → ANALYZING → VALIDATING → PUBLISHED} 로 한 칸씩만 나아가고, 공개 전이라면 어느 단계에서든
 * {@code FAILED} 로 끊을 수 있다. 건너뛰기를 막는 이유: 각 단계는 앞 단계의 산출물을 입력으로 받는다. 전사 없이 분석으로 넘어간 작업은 빈 입력으로 결과를 만들어 내고, 그 결과는 근거가 없어
 * 검증에서 통째로 걸러진다 — 8시간을 다 쓰고 빈 결과만 남는다(AI-006).
 *
 * <p>{@code PUBLISHED} 와 {@code FAILED} 는 끝이다. 공개된 결과를 되돌리는 경로는 없고, 실패한 작업을 다시 돌릴지는 재시도 정책(S15P11A105-107)이 따로 정한다.
 */
public final class PipelineStateMachine {

    private static final Map<PipelineStatus, Set<PipelineStatus>> NEXT_STAGES = Map.of(
            PipelineStatus.QUEUED, EnumSet.of(PipelineStatus.TRANSCRIBING, PipelineStatus.FAILED),
            PipelineStatus.TRANSCRIBING, EnumSet.of(PipelineStatus.ANALYZING, PipelineStatus.FAILED),
            PipelineStatus.ANALYZING, EnumSet.of(PipelineStatus.VALIDATING, PipelineStatus.FAILED),
            PipelineStatus.VALIDATING, EnumSet.of(PipelineStatus.PUBLISHED, PipelineStatus.FAILED),
            PipelineStatus.PUBLISHED, EnumSet.noneOf(PipelineStatus.class),
            PipelineStatus.FAILED, EnumSet.noneOf(PipelineStatus.class));

    /** 갈 곳이 남은 단계 = 아직 끝나지 않은 단계. 단계를 더하면 여기에도 자동으로 반영된다. */
    private static final Set<PipelineStatus> UNFINISHED_STAGES = NEXT_STAGES.entrySet().stream()
            .filter(stage -> !stage.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(PipelineStatus.class)));

    private PipelineStateMachine() {}

    /**
     * 현재 단계에서 요청한 단계로 나아갈 수 있는지.
     *
     * <p>같은 단계를 다시 요청한 경우는 거짓이다 — 그것은 전이가 아니라 중복 보고이고, 멱등 처리는 호출자의 몫이다.
     */
    public static boolean canAdvance(PipelineStatus from, PipelineStatus to) {
        return NEXT_STAGES.get(from).contains(to);
    }

    /**
     * 아직 끝나지 않은 단계들. 밀린 작업을 고르는 쪽이 쓴다.
     *
     * <p>끝난 단계를 빼는 대신 이 목록으로 지목하는 이유: {@code status NOT IN (...)} 은 인덱스 선행 컬럼의 부정 조건이라 범위 스캔이 되지 않아, MySQL 이 (status,
     * created_at) 인덱스를 두고도 테이블을 통째로 훑는다.
     */
    public static Set<PipelineStatus> unfinishedStages() {
        return UNFINISHED_STAGES;
    }
}
