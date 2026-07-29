package com.a105.zani.audioclip.infrastructure.websocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** 기동 시 만든 마스터 키. 프로세스 밖으로 나가지 않는다 — 나가는 것은 세션별 파생 토큰뿐이다. */
    private final byte[] masterKey;

    private final String urlTemplate;

    public AudioStreamEndpoint(AudioClipProperties properties) {
        this.urlTemplate = properties.streamUrlTemplate();
        this.masterKey = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(this.masterKey);
    }

    @Override
    public String streamUrlFor(long sessionId) {
        return urlTemplate.replace("{sessionId}", String.valueOf(sessionId)) + "?key=" + tokenFor(sessionId);
    }

    /**
     * 들어온 자격이 <b>이 세션의</b> 이번 기동 토큰인지.
     *
     * <p>세션마다 다른 토큰을 쓴다. 주소는 LiveKit 으로 나가 {@code EgressInfo} 와 그쪽 로그에 남을 수 있는데, 모든 세션이 한 값을 공유하면 하나만 새도 임의 세션의 버퍼에 PCM
     * 을 밀어넣을 수 있다. 남의 강의 링버퍼가 오염되면 그 강사의 전사가 통째로 망가진다. 마스터 키에서 세션별로 파생하면 새어도 피해가 그 세션 하나에 갇힌다.
     *
     * <p>토큰은 경로에서 읽은 세션 ID 로 다시 계산해 대조하므로, 다른 세션의 토큰을 들고 와 경로만 바꾸면 일치하지 않는다. 길이 차이로도 정보가 새지 않게 상수 시간 비교를 쓴다.
     */
    public boolean matches(long sessionId, String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), tokenFor(sessionId).getBytes(StandardCharsets.UTF_8));
    }

    private String tokenFor(long sessionId) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, MAC_ALGORITHM));
            return HexFormat.of()
                    .formatHex(mac.doFinal(String.valueOf(sessionId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            // HmacSHA256 은 모든 JRE 가 제공한다. 여기 오면 런타임이 규격을 벗어난 것이라 기동 자체가 잘못됐다.
            throw new IllegalStateException("HMAC is unavailable", impossible);
        }
    }
}
