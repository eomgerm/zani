package com.a105.zani.recording.infrastructure.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.application.port.MediaAccessPort;
import com.a105.zani.recording.infrastructure.config.RecordingProperties;

/**
 * 세션·만료 시각에 묶인 HMAC 자격. S3 presigned URL 을 자체 서명으로 대신한다(FRD §15.2).
 *
 * <p>발급과 검증이 같은 프로세스라 키를 설정으로 주고받을 필요가 없다. 기동할 때 난수로 만들어 메모리에만 두면 양쪽이 같은 값을 쓰고, 설정 파일이나 {@code docker inspect} 에도 남지
 * 않는다({@code AudioStreamEndpoint} 와 같은 방식).
 *
 * <p><b>서명 대상에 만료 시각을 넣는 것이 핵심이다.</b> 주소에만 있고 서명에 없으면 클라이언트가 값을 늘려 무기한 접근한다.
 *
 * <p>세션 ID 도 서명 대상이라, 한 세션의 주소를 들고 와 경로만 바꾸면 일치하지 않는다. 사람(memberId)은 묶지 않는다 — 공통 녹화는 그 수업 참여자 모두가 보는 자료라 사람별로 나눌 실익이
 * 없고, 넣으려면 내부 식별자가 주소와 프록시 로그에 남는다.
 *
 * <p><b>알려진 한계</b>: 재기동하면 키가 바뀌어 이미 나간 주소가 전부 401 이 된다. 클라이언트가 재발급으로 이어 재생하므로 사용자에게는 잠깐 끊기는 정도다. 인스턴스를 여러 대로 늘리면 키가 갈려
 * 같은 문제가 상시화되므로, 그때는 키를 공유 설정으로 옮겨야 한다.
 */
@Component
public class HmacMediaAccessAdapter implements MediaAccessPort {

    private static final int SECRET_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final byte[] masterKey;
    private final String urlTemplate;
    private final String thumbnailUrlTemplate;
    private final java.time.Duration ttl;
    private final Clock clock;

    public HmacMediaAccessAdapter(RecordingProperties properties, Clock clock) {
        this.urlTemplate = properties.mediaUrlTemplate();
        this.thumbnailUrlTemplate = properties.thumbnailUrlTemplate();
        this.ttl = properties.mediaUrlTtl();
        this.clock = clock;
        this.masterKey = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(this.masterKey);
    }

    @Override
    public IssuedMediaUrl issue(long sessionId) {
        return issueFor(sessionId, urlTemplate);
    }

    @Override
    public IssuedMediaUrl issueThumbnail(long sessionId) {
        return issueFor(sessionId, thumbnailUrlTemplate);
    }

    private IssuedMediaUrl issueFor(long sessionId, String template) {
        // 초 단위로 끊는다. 주소에 담기는 값과 서명 대상이 같아야 하는데, 나노초까지 남기면 왕복하면서 표현이 달라진다.
        Instant expiresAt = clock.instant().plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String url = template.replace("{sessionId}", String.valueOf(sessionId)) + "?expires="
                + expiresAt.getEpochSecond() + "&token=" + sign(sessionId, expiresAt);
        return new IssuedMediaUrl(url, expiresAt);
    }

    @Override
    public boolean matches(long sessionId, Instant expiresAt, String token) {
        if (expiresAt == null || token == null || !clock.instant().isBefore(expiresAt)) {
            return false;
        }
        // 길이 차이로도 정보가 새지 않게 상수 시간 비교를 쓴다.
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                sign(sessionId, expiresAt).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(long sessionId, Instant expiresAt) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, MAC_ALGORITHM));
            // 구분자를 넣어 (1, 23) 과 (12, 3) 이 같은 입력으로 뭉개지지 않게 한다.
            String payload = sessionId + "|" + expiresAt.getEpochSecond();
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 은 모든 JRE 가 제공한다. 여기 오면 런타임이 규격을 벗어난 것이라 기동 자체가 잘못됐다.
            throw new IllegalStateException("HMAC is unavailable", impossible);
        }
    }
}
