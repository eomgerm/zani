package com.a105.zani.recording.infrastructure.media;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.infrastructure.config.RecordingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class HmacMediaAccessAdapterTest {

    private static final long SESSION_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(NOW);
    private final HmacMediaAccessAdapter adapter = adapterWith(clock);

    private static HmacMediaAccessAdapter adapterWith(Clock clock) {
        return new HmacMediaAccessAdapter(
                new RecordingProperties(
                        "/srv/zani/recordings",
                        "/srv/zani/recordings",
                        TTL,
                        "https://zani.example/api/v1/sessions/{sessionId}/media"),
                clock);
    }

    private static String tokenOf(String url) {
        return url.substring(url.indexOf("&token=") + "&token=".length());
    }

    @Test
    @DisplayName("발급한 주소에 세션·만료·토큰이 담기고 TTL 만큼 유효하다")
    void an_issued_url_carries_the_credential() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(issued.url())
                .startsWith("https://zani.example/api/v1/sessions/100/media?expires=")
                .contains("&token=");
        assertThat(adapter.matches(SESSION_ID, issued.expiresAt(), tokenOf(issued.url())))
                .isTrue();
    }

    @Test
    @DisplayName("유효 기간이 지나면 서명이 맞아도 거절한다")
    void an_expired_credential_is_rejected() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);
        String token = tokenOf(issued.url());

        clock.now = issued.expiresAt().minusSeconds(1);
        assertThat(adapter.matches(SESSION_ID, issued.expiresAt(), token)).isTrue();

        clock.now = issued.expiresAt();
        assertThat(adapter.matches(SESSION_ID, issued.expiresAt(), token)).isFalse();
    }

    @Test
    @DisplayName("만료 시각을 늘려 잡으면 서명이 깨진다 — 주소만 고쳐 연장할 수 없다")
    void extending_the_expiry_breaks_the_signature() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);

        assertThat(adapter.matches(SESSION_ID, issued.expiresAt().plusSeconds(3600), tokenOf(issued.url())))
                .isFalse();
    }

    @Test
    @DisplayName("다른 세션의 토큰은 통하지 않는다 — 경로만 바꿔 남의 녹화를 열 수 없다")
    void a_token_of_another_session_does_not_transfer() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);

        assertThat(adapter.matches(SESSION_ID + 1, issued.expiresAt(), tokenOf(issued.url())))
                .isFalse();
    }

    @Test
    @DisplayName("빈 자격은 거절한다")
    void a_missing_credential_is_rejected() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);

        assertThat(adapter.matches(SESSION_ID, null, tokenOf(issued.url()))).isFalse();
        assertThat(adapter.matches(SESSION_ID, issued.expiresAt(), null)).isFalse();
    }

    @Test
    @DisplayName("기동마다 키가 다르다 — 다른 인스턴스의 토큰은 통하지 않는다")
    void each_boot_uses_its_own_key() {
        IssuedMediaUrl issued = adapter.issue(SESSION_ID);

        HmacMediaAccessAdapter rebooted = adapterWith(clock);

        assertThat(rebooted.matches(SESSION_ID, issued.expiresAt(), tokenOf(issued.url())))
                .isFalse();
    }

    /** 만료를 넘기는 순간을 보기 위한 가변 시계. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
