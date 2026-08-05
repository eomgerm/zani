package com.a105.zani.postclass.application.port;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가드가 <b>본문에 실릴 때의 크기</b>를 재는지 본다.
 *
 * <p>이스케이프 전 크기로 재면 실제 전송량을 약 10% 작게 본다. 그 차이는 게이트웨이가 본문을 조용히 자르고 업스트림이 "model not found" 를 돌려주는 형태로만 드러나므로, 측정 방식이
 * 되돌아가면 운영에서 원인을 찾을 수 없다.
 */
class GmsContentSizeGuardTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("이스케이프 전에는 상한 안이지만 전송 형태로 넘치는 payload 를 거절한다")
    void rejectsPayloadThatOnlyOverflowsAfterEscaping() {
        Map<String, Object> payload = payloadJustOverTheEscapedLimit();

        // 이 테스트가 노리는 상황인지 먼저 못 박는다. 이스케이프 전 기준이었다면 통과했을 크기여야 한다.
        assertThat(rawBytes(payload)).isLessThanOrEqualTo(GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES);
        assertThat(escapedBytes(payload)).isGreaterThan(GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES);

        assertThat(GmsContentSizeGuard.fits(mapper, payload)).isFalse();
    }

    @Test
    @DisplayName("전송 형태로 상한 바로 아래면 통과시킨다")
    void acceptsPayloadJustUnderTheEscapedLimit() {
        Map<String, Object> payload = payloadJustUnderTheEscapedLimit();

        assertThat(escapedBytes(payload)).isLessThanOrEqualTo(GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES);
        assertThat(GmsContentSizeGuard.fits(mapper, payload)).isTrue();
    }

    @Test
    @DisplayName("작은 payload 는 통과시킨다")
    void acceptsSmallPayload() {
        assertThat(GmsContentSizeGuard.fits(mapper, Map.of("sections", List.of("판별식", "근의 공식"))))
                .isTrue();
    }

    /**
     * 짧은 레코드를 늘려 이스케이프 뒤 상한을 막 넘긴 payload.
     *
     * <p>짧은 레코드로 채우는 이유: 이스케이프 증가분은 바이트당 따옴표 수에 비례하므로 짧은 레코드가 많을수록 커진다. 그래야 "이스케이프 전에는 상한 안" 이라는 조건을 만들 수 있다.
     */
    private Map<String, Object> payloadJustOverTheEscapedLimit() {
        return growUntilEscapedOverflow(true);
    }

    /** 위와 같은 방식으로 채우되 상한을 넘기기 직전에서 멈춘 payload. */
    private Map<String, Object> payloadJustUnderTheEscapedLimit() {
        return growUntilEscapedOverflow(false);
    }

    private Map<String, Object> growUntilEscapedOverflow(boolean overflowing) {
        List<String> records = new ArrayList<>();
        Map<String, Object> previous = payloadOf(records);
        while (true) {
            records.add("obs-%05d".formatted(records.size()));
            Map<String, Object> candidate = payloadOf(records);
            if (escapedBytes(candidate) > GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES) {
                return overflowing ? candidate : previous;
            }
            previous = candidate;
        }
    }

    private static Map<String, Object> payloadOf(List<String> records) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("observations", List.copyOf(records));
        return payload;
    }

    private int rawBytes(Object payload) {
        return mapper.writeValueAsBytes(payload).length;
    }

    private int escapedBytes(Object payload) {
        return mapper.writeValueAsBytes(mapper.writeValueAsString(payload)).length;
    }
}
