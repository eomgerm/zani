package com.a105.zani.session.domain.model;

import java.util.Optional;

/**
 * 미디어 서버에서 참가자를 가리키는 identity. 형태는 {@code p-{participantId}} 다.
 *
 * <p>회원 ID 가 아니라 참가자 ID 를 쓴다. 참가자 행은 세션 안에서만 유효하므로, 다른 수업의 identity 가 이 수업의 누군가로 해석되지 않는다.
 *
 * <p>규칙을 세션 도메인이 소유한다. 토큰을 발급할 때 심는 쪽과 webhook 에서 되읽는 쪽이 같은 규칙을 봐야 하고, 규칙이 두 곳에 복사되면 한쪽만 바뀐다.
 */
public final class ParticipantIdentity {

    private static final String PREFIX = "p-";

    private ParticipantIdentity() {}

    public static String of(long participantId) {
        return PREFIX + participantId;
    }

    /**
     * identity 에서 참가자 ID 를 되읽는다.
     *
     * <p>규칙에 맞지 않으면 비어 있다. Egress·시스템 참가자가 이 경로로 들어오는데, 그들은 인원과 출석에서 빠져야 하므로 여기서 걸러지는 게 맞다.
     */
    public static Optional<Long> parse(String identity) {
        if (identity == null || !identity.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(identity.substring(PREFIX.length())));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }
}
