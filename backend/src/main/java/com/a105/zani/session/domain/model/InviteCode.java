package com.a105.zani.session.domain.model;

import java.util.regex.Pattern;

import com.a105.zani.session.domain.exception.InvalidInviteCodeException;

/**
 * 세션 초대 코드. 저장·조회에 쓰는 canonical 값은 대문자 영숫자 8자다(가이드 §6).
 *
 * <p>UI는 {@code A7KM-2PQR}처럼 하이픈을 넣어 보여주고 사용자는 소문자로 입력할 수 있으므로, 입력은 하이픈·공백을 제거하고 대문자로 올린 뒤 검증한다.
 *
 * <p>검증 알파벳({@code A-Z0-9})이 생성 알파벳({@link #GENERATION_ALPHABET})보다 넓은 것은 의도적이다. 생성은 모호한 글자를 피하지만, 이 규칙이 생기기 전에 발급된
 * 코드에는 {@code O}·{@code 0}·{@code 1}이 이미 섞여 있어 그 코드로도 계속 입장할 수 있어야 한다.
 */
public final class InviteCode {

    public static final int LENGTH = 8;

    /** 생성에 쓰는 알파벳. 서로 헷갈리는 {@code I}, {@code L}, {@code O}, {@code 0}, {@code 1}을 뺐다. */
    public static final String GENERATION_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final Pattern CANONICAL = Pattern.compile("[A-Z0-9]{" + LENGTH + "}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final int DISPLAY_GROUP_SIZE = 4;

    private final String value;

    private InviteCode(String value) {
        this.value = value;
    }

    /** 사용자 입력·표시형을 canonical 값으로 정규화한다. 형식이 어긋나면 {@link InvalidInviteCodeException}. */
    public static InviteCode normalize(String raw) {
        if (raw == null) {
            throw new InvalidInviteCodeException();
        }
        String canonical = SEPARATORS.matcher(raw).replaceAll("").toUpperCase();
        if (!CANONICAL.matcher(canonical).matches()) {
            throw new InvalidInviteCodeException();
        }
        return new InviteCode(canonical);
    }

    public String value() {
        return value;
    }

    /** 사람이 읽고 옮겨 적기 쉬운 표시형: {@code A7KM-2PQR}. */
    public String display() {
        return value.substring(0, DISPLAY_GROUP_SIZE) + "-" + value.substring(DISPLAY_GROUP_SIZE);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InviteCode inviteCode && value.equals(inviteCode.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
