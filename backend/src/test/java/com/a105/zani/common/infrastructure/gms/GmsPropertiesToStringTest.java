package com.a105.zani.common.infrastructure.gms;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설정 덤프가 키를 가리고 나머지는 다 보여 주는지 검증한다.
 *
 * <p>{@code toString} 을 손으로 쓴 이유는 {@code apiKey} 하나만 가려야 하는데 record 의 자동 생성 구현은 필드를 골라 가릴 수 없기 때문이다. 대가로 필드를 더할 때 목록을
 * 함께 고쳐야 하고, 컴파일러는 빠뜨린 것을 잡아 주지 않는다 — 실제로 {@code postclassTranscribeTimeout} 이 빠져 설정 덤프에서 보이지 않았다(S15P11A105-247 리뷰).
 *
 * <p>그래서 필드 이름을 반사로 훑어 확인한다. 다음에 필드를 더하고 목록을 잊으면 이 테스트가 먼저 깨진다.
 */
class GmsPropertiesToStringTest {

    /** 가려야 하는 값. 이 문자열이 출력에 그대로 나오면 안 된다. */
    private static final String SECRET = "sk-do-not-leak-0123456789";

    private static GmsProperties properties(String apiKey) {
        return new GmsProperties(
                "https://gms.test",
                apiKey,
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                Duration.ofSeconds(180),
                "ko",
                "gpt-5.4-mini",
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60),
                Duration.ofSeconds(25));
    }

    @Test
    void 모든_필드_이름이_출력에_들어간다() {
        // 하나라도 빠지면 그 설정값을 운영에서 확인할 방법이 없다. "timeout 을 올렸는데 왜 그대로인가" 에
        // 답하려면 덤프에 보여야 한다.
        String dumped = properties(SECRET).toString();

        List<String> missing = new ArrayList<>();
        for (RecordComponent component : GmsProperties.class.getRecordComponents()) {
            if (!dumped.contains(component.getName() + "=")) {
                missing.add(component.getName());
            }
        }

        assertEquals(List.of(), missing, "toString 목록에서 빠진 필드: " + missing + "\n실제 출력: " + dumped);
    }

    @Test
    void apiKey_는_가려진다() {
        String dumped = properties(SECRET).toString();

        assertFalse(dumped.contains(SECRET), "API key 가 그대로 노출됐다: " + dumped);
        assertTrue(dumped.contains("apiKey=****"), dumped);
    }

    @Test
    void 키가_없으면_없다고_표시한다() {
        // "****" 로 두면 키가 설정된 것처럼 보인다. 기동 후 401 을 받고 나서야 원인을 찾게 된다.
        assertTrue(properties(null).toString().contains("apiKey=(unset)"));
        assertTrue(properties("  ").toString().contains("apiKey=(unset)"));
    }

    @Test
    void 나머지_값은_그대로_보인다() {
        String dumped = properties(SECRET).toString();

        assertTrue(dumped.contains("baseUrl=https://gms.test"), dumped);
        assertTrue(dumped.contains("transcribeTimeout=PT20S"), dumped);
        // 이 값이 빠져 있던 것이 리뷰 지적이다.
        assertTrue(dumped.contains("postclassTranscribeTimeout=PT3M"), dumped);
        assertTrue(dumped.contains("tipTimeout=PT6S"), dumped);
    }
}
