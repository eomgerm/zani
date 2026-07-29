package com.a105.zani.audioclip.infrastructure.websocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.application.port.AudioStreamEndpointPort;
import com.a105.zani.audioclip.infrastructure.config.AudioClipProperties;

/**
 * 내부 오디오 수신 경로의 주소와 접속 자격을 함께 소유한다.
 *
 * <p>주소를 발급하는 쪽(Egress 시작)과 검증하는 쪽(WebSocket 수신)이 모두 이 프로세스라, 자격을 설정으로 주고받을 필요가 없다. 기동할 때 난수로 만들어 메모리에만 두면 양쪽이 같은 값을
 * 쓴다. 설정 파일이나 {@code docker inspect} 에 남지 않아 노출면도 줄어든다.
 *
 * <p><b>알려진 한계</b>: 재기동하면 값이 바뀌어 진행 중이던 스트림의 재연결이 거부된다. 그런데 그 Egress 를 다시 세우는 경로가 없다 — outbox 행은 이미 COMPLETED 라 릴레이가 다시
 * 집지 않고({@code requeueExpiredClaims} 는 IN_PROGRESS 만 되살린다), {@code track_published} 도 이미 발행된 트랙에는 다시 오지 않는다. 그래서 배포 시점에
 * 진행 중이던 강의는 남은 시간 동안 코칭 오디오를 받지 못한다. 수업과 녹화는 영향받지 않는다.
 *
 * <p>없애려면 자격을 재기동에도 살아남게 하거나(그러면 설정으로 관리할 값이 다시 생긴다) 기동 시 활성 트랙을 훑어 스트림 Egress 를 재조정해야 한다. 둘 다 이 티켓 범위 밖이라 한계로 남긴다.
 */
@Component
public class AudioStreamEndpoint implements AudioStreamEndpointPort {

    private static final int SECRET_BYTES = 32;

    private final String urlTemplate;
    private final String secret;

    public AudioStreamEndpoint(AudioClipProperties properties) {
        this.urlTemplate = properties.streamUrlTemplate();
        byte[] random = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        this.secret = HexFormat.of().formatHex(random);
    }

    @Override
    public String streamUrlFor(long sessionId) {
        return urlTemplate.replace("{sessionId}", String.valueOf(sessionId)) + "?key=" + secret;
    }

    /** 들어온 자격이 이번 기동의 것인지. 길이 차이로도 정보가 새지 않게 상수 시간 비교를 쓴다. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }
}
