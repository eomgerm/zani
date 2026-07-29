package com.a105.zani.attention;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판정 상태 저장소가 죽었을 때 API가 503으로 응답하는지 확인한다. 어댑터가 예외로 바꾸는 것은 {@code AttentionStateRedisFailureTest}가 보고, 여기서는 그 예외가 HTTP
 * 503과 에러코드까지 이어지는지를 본다. 코칭만 멈추고 수업은 이어져야 한다.
 *
 * <p>실제 Redis를 끄는 대신 항상 실패하는 포트 구현으로 대체한다. 같은 Redis를 쓰는 다른 테스트에 영향을 주지 않는다.
 */
@SpringBootTest
class AttentionEventStoreFailureIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_100_920L;
    private static final long STUDENT_ID = 9_100_921L;
    private static final long SESSION_ID = 9_100_922L;
    private static final long PARTICIPANT_ID = 9_100_923L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Instant SESSION_STARTED_AT = NOW.minusSeconds(300);

    /** 관측 시각은 세션 시작 기준으로 잡는다. 고정 시각을 쓰면 시간선 검증에 먼저 걸려 503 을 볼 수 없다. */
    private static final String BODY = "{\"outcome\":\"BARELY_ENGAGED\",\"lowEngagement\":true,\"windowStartedAt\":\""
            + SESSION_STARTED_AT.plusSeconds(60)
            + "\",\"observedAt\":\""
            + SESSION_STARTED_AT.plusSeconds(70)
            + "\",\"signalQuality\":0.92,\"featureSchemaVersion\":\"mediapipe_98_v1\","
            + "\"engineVersion\":\"e0g-1\",\"clientEventId\":\"store-down-1\"}";

    @TestConfiguration
    static class FailingStoreConfig {

        /** 저장소가 죽은 상태를 흉내 낸다. 저장소 장애는 어느 호출에서든 같은 애플리케이션 예외로 올라와야 한다. */
        @Bean
        @Primary
        AttentionStatePort failingAttentionStatePort() {
            return new AttentionStatePort() {

                @Override
                public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void clearEvent(long sessionId, long participantId, String clientEventId) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void recordCurrentState(
                        long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void includeInDenominator(long sessionId, long participantId) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public DetectionRunCounters applyObservation(
                        long sessionId,
                        long participantId,
                        DetectionRunTransition transition,
                        long observedOffsetMs,
                        Duration ttl) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public void resetRuns(long sessionId, long participantId) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }

                @Override
                public OptionalLong lastAppliedOffsetMs(long sessionId, long participantId) {
                    throw new AttentionStateUnavailableException(new IllegalStateException("store down"));
                }
            };
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "저장소 장애 테스트 강사");
        insertMember(STUDENT_ID, "저장소 장애 테스트 학생");
        insertLiveSession();
        insertStudentParticipant();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void answersServiceUnavailableWhenTheJudgementStoreIsDown() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", SESSION_ID)
                        .header("Authorization", "Bearer " + studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTENTION_STATE_001"));
    }

    private String studentToken() {
        return tokenProvider.issueAccessToken(String.valueOf(STUDENT_ID)).value();
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(NOW),
                utc(NOW));
    }

    private void insertLiveSession() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "저장소 장애 테스트",
                "ATTEN920",
                utc(SESSION_STARTED_AT),
                utc(NOW),
                utc(NOW));
    }

    private void insertStudentParticipant() {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?)",
                PARTICIPANT_ID,
                SESSION_ID,
                STUDENT_ID,
                utc(NOW),
                utc(NOW),
                utc(NOW),
                utc(NOW));
    }
}
