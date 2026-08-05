package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분석 단계 후보 조회와 임대 선점을 실제 MySQL 로 확인한다(S15P11A105-304).
 *
 * <p>{@code next_attempt_at} 하나로 "아무도 안 잡음 / 임대 만료 / 처리 중" 세 상태를 가르므로, 조건 하나가 어긋나면 같은 세션이 두 번 분석되거나 영원히 발견되지 않는다. 둘 다
 * 대역 으로는 드러나지 않는다.
 */
/*
 * 트랜잭션 안에서 돈다. 선점은 @Modifying UPDATE 라 트랜잭션이 없으면 저장소가 거절하는데, 실제 호출자인
 * TryClaimAnalysisService 는 @Transactional 안에서 부르므로 그 조건을 여기서도 맞춘다.
 */
@SpringBootTest
@Transactional
class PipelineJobAnalysisQueryTest {

    private static final long OLDER_SESSION_ID = 9_304_001L;
    private static final long NEWER_SESSION_ID = 9_304_002L;
    private static final long OTHER_STAGE_SESSION_ID = 9_304_003L;

    @Autowired
    private PipelineJobPort pipelineJobPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cleanUpRows();
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    @DisplayName("전사가 방금 넘긴 작업은 대기 시각이 비어 있어도 담는다 — 전사와 반대 규칙이다")
    void picks_up_an_analyzing_job_without_a_wait() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), null, 0);

        assertThat(candidates()).containsExactly(OLDER_SESSION_ID);
    }

    @Test
    @DisplayName("임대가 끝났거나 재시도 기한이 지난 작업을 담고, 오래 등록된 것부터 준다")
    void picks_up_expired_leases_oldest_first() {
        insertJob(NEWER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(60), now.minusSeconds(1), 2);
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), null, 0);

        assertThat(candidates()).containsExactly(OLDER_SESSION_ID, NEWER_SESSION_ID);
    }

    @Test
    @DisplayName("임대가 살아 있는 작업은 담지 않는다 — 담으면 같은 세션이 겹쳐 분석된다")
    void skips_a_job_whose_lease_is_still_alive() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), now.plusSeconds(1_800), 0);

        assertThat(candidates()).isEmpty();
    }

    @Test
    @DisplayName("분석 단계가 아닌 작업은 담지 않는다")
    void skips_jobs_in_other_stages() {
        insertJob(OTHER_STAGE_SESSION_ID, PipelineStatus.TRANSCRIBING, now.minusSeconds(600), null, 0);
        insertJob(NEWER_SESSION_ID, PipelineStatus.PUBLISHED, now.minusSeconds(600), null, 0);
        insertJob(OLDER_SESSION_ID, PipelineStatus.FAILED, now.minusSeconds(600), null, 0);

        assertThat(candidates()).isEmpty();
    }

    /**
     * 공개가 거절되면 작업은 {@code VALIDATING} 에 남는다. 담지 않으면 그 세션은 8시간 마감까지 멈춘다.
     *
     * <p>이어받은 실행은 세 분석의 멱등 겹을 GMS 없이 통과해 공개만 다시 시도한다.
     */
    @Test
    @DisplayName("공개를 기다리는 VALIDATING 작업도 담는다")
    void picks_up_a_validating_job_waiting_to_publish() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.VALIDATING, now.minusSeconds(600), now.minusSeconds(1), 1);

        assertThat(candidates()).containsExactly(OLDER_SESSION_ID);
    }

    @Test
    @DisplayName("건수 제한을 지킨다")
    void honours_the_limit() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), null, 0);
        insertJob(NEWER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(60), null, 0);

        assertThat(pipelineJobPort.findDueAnalysisSessionIds(now, 1)).hasSize(1);
    }

    /** 선점은 실패가 아니다. 시도 횟수를 올리면 한 번도 실패하지 않은 세션이 재시도 상한에 걸린다. */
    @Test
    @DisplayName("선점은 대기 시각만 임대 만료로 밀고 시도 횟수와 단계를 그대로 둔다")
    void claiming_only_moves_the_wait() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), null, 3);
        Instant leaseUntil = now.plusSeconds(3_600);

        pipelineJobPort.claimAnalysis(OLDER_SESSION_ID, leaseUntil, now);

        PipelineJobState state = pipelineJobPort.find(OLDER_SESSION_ID).orElseThrow();
        assertThat(state.nextAttemptAt()).isEqualTo(leaseUntil);
        assertThat(state.attemptCount()).isEqualTo(3);
        assertThat(state.status()).isEqualTo(PipelineStatus.ANALYZING);
    }

    @Test
    @DisplayName("선점한 작업은 다음 후보 조회에서 빠진다")
    void a_claimed_job_leaves_the_candidate_list() {
        insertJob(OLDER_SESSION_ID, PipelineStatus.ANALYZING, now.minusSeconds(600), null, 0);

        pipelineJobPort.claimAnalysis(OLDER_SESSION_ID, now.plusSeconds(3_600), now);

        assertThat(candidates()).isEmpty();
    }

    /**
     * 이 테스트가 심은 세션만 골라낸다.
     *
     * <p>데모 시드가 {@code ANALYZING} 세션 하나를 상주시키므로({@code R__demo_seed.sql}) 조회 결과를 통째로 비교하면 그 행 때문에 깨진다. 지우면 시드를 다시 넣을
     * 방법이 없다.
     */
    private List<Long> candidates() {
        List<Long> mine = List.of(OLDER_SESSION_ID, NEWER_SESSION_ID, OTHER_STAGE_SESSION_ID);
        return pipelineJobPort.findDueAnalysisSessionIds(now, 1_000).stream()
                .filter(mine::contains)
                .toList();
    }

    private void insertJob(
            long sessionId, PipelineStatus status, Instant createdAt, Instant nextAttemptAt, int attemptCount) {
        jdbcTemplate.update(
                "INSERT INTO pipeline_jobs (id, session_id, status, attempt_count, next_attempt_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId,
                sessionId,
                status.name(),
                attemptCount,
                nextAttemptAt == null ? null : utc(nextAttemptAt),
                utc(createdAt),
                utc(createdAt));
    }

    private void cleanUpRows() {
        jdbcTemplate.update(
                "DELETE FROM pipeline_jobs WHERE session_id IN (?, ?, ?)",
                OLDER_SESSION_ID,
                NEWER_SESSION_ID,
                OTHER_STAGE_SESSION_ID);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 조회가 목록 타입을 그대로 돌려주는지 — 빈 결과가 null 이 되면 스케줄러가 NPE 로 주기를 통째로 잃는다. */
    @Test
    @DisplayName("후보가 없으면 빈 목록이다")
    void returns_an_empty_list_without_candidates() {
        assertThat(candidates()).isNotNull().isEmpty();
    }
}
