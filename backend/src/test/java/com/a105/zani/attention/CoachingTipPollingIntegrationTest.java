package com.a105.zani.attention;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.LockSupport;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.audioclip.infrastructure.buffer.InstructorAudioBuffer;
import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 팁 폴링 엔드포인트의 전 구간 검증. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 *
 * <p>임계값 경계(29%/30%, 59초/60초, 분모 0)는 {@code CoachingTriggerPolicyTest} 가 전수로 다룬다. 여기서는 폴링 계약과 권한, 그리고 실제 Redis·오디오 버퍼를
 * 거친 트리거 한 바퀴만 본다.
 */
@SpringBootTest
class CoachingTipPollingIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_200_910L;
    private static final long STUDENT_ID = 9_200_911L;
    private static final long SESSION_ID = 9_200_912L;
    private static final long PARTICIPANT_ID = 9_200_913L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_200_914L;

    private static final String OPEN_KEY = "attention:" + SESSION_ID + ":coaching:open";
    private static final String LAST_TIP_KEY = "attention:" + SESSION_ID + ":coaching:last-tip";
    private static final String PRESENCE_KEY = "session:" + SESSION_ID + ":presence:" + PARTICIPANT_ID;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CoachingTriggerStatePort coachingTriggerStatePort;

    @Autowired
    private InstructorAudioBuffer instructorAudioBuffer;

    @Autowired
    @Qualifier("coachingTipExecutor") private ThreadPoolTaskExecutor coachingTipExecutor;

    private MockMvc mockMvc;

    /** 시각은 테스트마다 새로 잡는다. 고정 시각을 쓰면 3시간 뒤부터 만료 스케줄러가 세션을 끝낸다. */
    private Instant now;

    private Instant sessionStartedAt;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        sessionStartedAt = now.minusSeconds(600);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "코칭 팁 테스트 강사");
        insertMember(STUDENT_ID, "코칭 팁 테스트 학생");
        insertLiveSession();
        insertParticipant(PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        clearState();
    }

    @AfterEach
    void cleanUp() {
        awaitCoachingTasks();
        clearState();
        instructorAudioBuffer.release(SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM coaching_history_response_counts WHERE coaching_history_id IN"
                        + " (SELECT id FROM coaching_histories WHERE session_id = ?)",
                SESSION_ID);
        jdbcTemplate.update("DELETE FROM coaching_histories WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE id IN (?, ?)", PARTICIPANT_ID, INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    private void awaitCoachingTasks() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((coachingTipExecutor.getActiveCount() > 0
                        || !coachingTipExecutor
                                .getThreadPoolExecutor()
                                .getQueue()
                                .isEmpty())
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    @Test
    void answersWithAnEmptyEnvelopeWhenThereIsNothingToShow() throws Exception {
        // 204 로 하면 본문이 없어 미표시 사유를 함께 전달할 수 없다.
        //
        // doesNotExist() 는 쓰지 않는다 — 명시적 null 과 키 없음을 둘 다 통과시켜 이 결정을 지켜 주지 못한다.
        // isEmpty() 는 null 만 통과하고 키가 사라지면 실패한다. 직렬화 설정 한 줄(default-property-inclusion:
        // non_null)이면 data 가 {} 로 나가는데, 그것을 잡아내는 것이 이 테스트의 목적이다.
        poll(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerId").isEmpty())
                .andExpect(jsonPath("$.data.tip").isEmpty())
                .andExpect(jsonPath("$.data.unavailableReason").isEmpty());
    }

    @Test
    void opensATriggerOnceTheRatioAndTheAudioBothQualify() throws Exception {
        markCountedStudent();
        fillInstructorAudio(Duration.ofSeconds(90));

        poll(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerId").isString())
                // 전사와 문구 생성을 기다리지 않으므로 첫 응답에는 팁이 없다.
                .andExpect(jsonPath("$.data.tip").isEmpty())
                .andExpect(jsonPath("$.data.unavailableReason").isEmpty());

        assertNotNull(redisTemplate.opsForValue().get(OPEN_KEY));
        // 쿨타임은 트리거를 연 순간부터다. TTL 이 없으면 키가 남아 다음 수업까지 트리거를 막는다.
        Long ttl = redisTemplate.getExpire(OPEN_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(10).toSeconds(), "쿨타임 TTL 이 어긋납니다: " + ttl);
    }

    @Test
    void keepsReturningTheSameTriggerWhileTheCooldownHolds() throws Exception {
        markCountedStudent();
        fillInstructorAudio(Duration.ofSeconds(90));

        String first = triggerIdOf(poll(INSTRUCTOR_ID));
        String second = triggerIdOf(poll(INSTRUCTOR_ID));

        // 프론트는 같은 triggerId 를 다시 받으면 카드를 다시 띄우지 않는다(티켓 86).
        assertEquals(first, second);
    }

    @Test
    void handsOverACompletedTipOnTheNextPoll() throws Exception {
        markCountedStudent();
        fillInstructorAudio(Duration.ofSeconds(90));
        String triggerId = triggerIdOf(poll(INSTRUCTOR_ID));

        coachingTriggerStatePort.completeOutcome(
                SESSION_ID,
                CoachingOutcome.completed(
                        triggerId,
                        new CoachingTip(
                                CoachingTipType.CONFUSED,
                                "지금 다시 짚고 갈 개념이 있습니다",
                                "전체 학생의 100%가 헷갈려하고 있습니다.\n재귀 호출의 종료 조건을 예시와 함께 다시 설명해 주세요.",
                                "재귀 호출의 종료 조건")));

        poll(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerId").value(triggerId))
                .andExpect(jsonPath("$.data.tip.tipType").value("CONFUSED"))
                // 문구는 LLM 이 채운 자유 텍스트라 줄바꿈이 그대로 살아 돌아와야 한다.
                .andExpect(jsonPath("$.data.tip.message").value(Matchers.containsString("\n")))
                .andExpect(jsonPath("$.data.tip.targetConcept").value("재귀 호출의 종료 조건"));

        // 다음 트리거가 같은 유형을 반복하지 않도록 직전 팁을 남긴다.
        String lastTip = redisTemplate.opsForValue().get(LAST_TIP_KEY);
        assertNotNull(lastTip);
        assertTrue(lastTip.startsWith(CoachingTipType.CONFUSED.name() + "|"), "직전 팁 값이 어긋납니다: " + lastTip);
    }

    @Test
    void ignoresACompletionFromATriggerThatIsNoLongerOpen() throws Exception {
        markCountedStudent();
        fillInstructorAudio(Duration.ofSeconds(90));
        String stale = triggerIdOf(poll(INSTRUCTOR_ID));

        // 파이프라인이 쿨타임(10분)보다 오래 걸린 상황이다. 그 사이 키가 만료되고 다음 폴링이 새 트리거를 연다.
        redisTemplate.delete(OPEN_KEY);
        String current = triggerIdOf(poll(INSTRUCTOR_ID));
        // 둘이 실제로 다른 트리거여야 아래 단정이 뜻을 갖는다. 같은 값이면 무엇을 확인해도 통과한다.
        assertNotEquals(stale, current);

        coachingTriggerStatePort.completeOutcome(
                SESSION_ID,
                CoachingOutcome.completed(
                        stale,
                        new CoachingTip(
                                CoachingTipType.NON_RESPONSE,
                                "학생 반응을 확인해 주세요",
                                "전체 학생의 100%가 질문에 응답하지 않았어요.",
                                // 무응답 팁은 §8 에 자리표시자가 없어 LLM 을 부르지 않는다(티켓 204).
                                null)));

        // 늦게 끝난 트리거를 그대로 받으면 강사는 10분 전 팁을 이전 triggerId 로 받고 새 트리거는 사라진다.
        poll(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerId").value(current))
                .andExpect(jsonPath("$.data.tip").isEmpty());

        // 직전 팁도 남기지 않는다. 남기면 다음 트리거가 뜨지도 않은 팁을 피하려 유형을 바꾼다.
        assertNull(redisTemplate.opsForValue().get(LAST_TIP_KEY));
    }

    @Test
    void doesNotRaiseATipForAStudent() throws Exception {
        markCountedStudent();
        fillInstructorAudio(Duration.ofSeconds(90));

        poll(STUDENT_ID).andExpect(status().isForbidden());

        // 학생의 폴링이 트리거를 열면 쿨타임이 강사 몰래 소모된다.
        assertTrue(redisTemplate.opsForValue().get(OPEN_KEY) == null);
    }

    @Test
    void refusesAPollWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/coaching-tip", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tellsTheClientToStopPollingOnceTheSessionEnded() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'ENDED' WHERE id = ?", SESSION_ID);

        poll(INSTRUCTOR_ID).andExpect(status().isConflict());
    }

    private ResultActions poll(long memberId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/coaching-tip", SESSION_ID)
                .header(
                        "Authorization",
                        "Bearer "
                                + tokenProvider
                                        .issueAccessToken(String.valueOf(memberId))
                                        .value()));
    }

    private String triggerIdOf(ResultActions actions) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.triggerId");
    }

    /** 분모에 세어지고 분자에도 들어가는 학생 한 명을 만든다. 분모는 1분 이상 접속한 학생만 센다(티켓 84). */
    private void markCountedStudent() {
        redisTemplate
                .opsForValue()
                .set(PRESENCE_KEY, "since:" + now.minusSeconds(120).toEpochMilli(), Duration.ofMinutes(5));
        redisTemplate
                .opsForValue()
                .set(
                        "attention:" + SESSION_ID + ":significant:" + AttentionState.CONFUSED.name() + ":"
                                + PARTICIPANT_ID,
                        "1",
                        Duration.ofMinutes(5));
    }

    /** 강사 오디오 버퍼를 무음으로 채운다. 실제로는 LiveKit egress 가 서버로 스트리밍한다(티켓 198). */
    private void fillInstructorAudio(Duration length) {
        int sampleRate = 48_000;
        int bytesPerSample = 2;
        int chunk = sampleRate * bytesPerSample;
        long remaining = length.toSeconds();
        for (long second = 0; second < remaining; second++) {
            instructorAudioBuffer.append(SESSION_ID, new byte[chunk]);
        }
    }

    private void clearState() {
        redisTemplate.delete(OPEN_KEY);
        redisTemplate.delete(LAST_TIP_KEY);
        redisTemplate.delete(PRESENCE_KEY);
        for (AttentionState state : AttentionState.values()) {
            redisTemplate.delete("attention:" + SESSION_ID + ":significant:" + state.name() + ":" + PARTICIPANT_ID);
        }
        redisTemplate.delete("attention:" + SESSION_ID + ":excluded:" + PARTICIPANT_ID);
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(now),
                utc(now));
    }

    private void insertLiveSession() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "코칭 팁 폴링 테스트",
                "COACH910",
                utc(sessionStartedAt),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long memberId, String role) {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", participantId);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                participantId,
                SESSION_ID,
                memberId,
                role,
                utc(now),
                utc(now),
                utc(now),
                utc(now));
    }
}
