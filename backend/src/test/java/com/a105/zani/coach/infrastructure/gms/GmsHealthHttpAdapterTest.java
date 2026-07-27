package com.a105.zani.coach.infrastructure.gms;

import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** 실제 GMS 호출 어댑터의 응답별 처리를 검증한다. 성공은 true, 401·402·5xx·timeout·연결 실패는 예외 없이 false 로 흡수해야 한다. */
class GmsHealthHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String PROBE_URL = BASE_URL + "/v1/chat/completions";

    private GmsProperties properties() {
        return new GmsProperties(
                BASE_URL, "test-key", "whisper-1", "gpt-4.1-nano", false, Duration.ofSeconds(3), Duration.ofSeconds(2));
    }

    private record Fixture(GmsHealthHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GmsHealthHttpAdapter(builder.build(), properties()), server);
    }

    @Test
    void reportsReachableOnSuccessAndSendsMinimalProbe() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(PROBE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("gpt-4.1-nano")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"max_tokens\":1")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", MediaType.APPLICATION_JSON));

        assertTrue(fixture.adapter().isGmsReachable());
        fixture.server().verify();
    }

    @Test
    void reportsUnreachableOnUnauthorized() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(PROBE_URL))
                .andRespond(MockRestResponseCreators.withUnauthorizedRequest()
                        .body("{\"message\":\"[GMS 에러] Invalid or expired GMS key\",\"statusCode\":401}"));

        assertFalse(fixture.adapter().isGmsReachable());
    }

    @Test
    void reportsUnreachableWhenCreditExhausted() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(PROBE_URL))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .body("{\"message\":\"credit exhausted\"}"));

        assertFalse(fixture.adapter().isGmsReachable());
    }

    @Test
    void reportsUnreachableOnServerError() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(PROBE_URL))
                .andRespond(MockRestResponseCreators.withServerError().body("{\"message\":\"unavailable\"}"));

        assertFalse(fixture.adapter().isGmsReachable());
    }

    @Test
    void reportsUnreachableOnTimeout() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(PROBE_URL)).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        assertFalse(fixture.adapter().isGmsReachable());
    }
}
