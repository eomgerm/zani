package com.a105.zani.session.domain.model;

import java.util.regex.Pattern;

import com.a105.zani.session.domain.exception.InvalidInviteCodeException;

/**
 * 초대 코드. 저장 형태는 대문자 영숫자 8자다.
 *
 * <p>사람이 옮겨 적는 값이라 들어오는 형태가 일정하지 않다. 표시형(`GPH7-GQ5Q`)으로 복사하거나 소문자로 타이핑하거나 공백이 끼어 온다. 브라우저에서도 한 번 맞춰 보내지만
 * (`canonicalInviteCode`), 직접 API 를 부르거나 다른 클라이언트가 붙으면 그 정규화를 거치지 않는다. 그래서 서버가 다시 맞춘다.
 */
public final class InviteCode {

    private static final int LENGTH = 8;
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern CANONICAL = Pattern.compile("^[A-Z0-9]{8}$");

    private InviteCode() {}

    /**
     * 저장 형태로 맞춘다. 하이픈·공백을 지우고 대문자로 올린다.
     *
     * @throws InvalidInviteCodeException 정규화한 결과가 대문자 영숫자 8자가 아니면
     */
    public static String canonicalize(String raw) {
        if (raw == null) {
            throw new InvalidInviteCodeException();
        }
        String canonical = SEPARATORS.matcher(raw).replaceAll("").toUpperCase();
        if (!CANONICAL.matcher(canonical).matches()) {
            throw new InvalidInviteCodeException();
        }
        return canonical;
    }

    public static int length() {
        return LENGTH;
    }
}
