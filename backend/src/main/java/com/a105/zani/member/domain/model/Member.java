package com.a105.zani.member.domain.model;

import com.a105.zani.member.domain.exception.InvalidDisplayNameException;
import com.a105.zani.member.domain.exception.InvalidGoogleIdentityException;

public class Member {

    /** members.display_name 컬럼 길이와 같다 — 여기서 막지 않으면 저장 시점에 잘려 나간다. */
    public static final int DISPLAY_NAME_MAX_LENGTH = 100;

    private static final String WITHDRAWN_GOOGLE_SUBJECT_PREFIX = "withdrawn:";

    private final Long id;
    private final String googleSubject;
    private final String email;
    private final String displayName;
    private final String profileImageUrl;
    private final boolean reportEmailEnabled;

    private Member(
            Long id,
            String googleSubject,
            String email,
            String displayName,
            String profileImageUrl,
            boolean reportEmailEnabled) {
        this.id = id;
        this.googleSubject = googleSubject;
        this.email = email;
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
        this.reportEmailEnabled = reportEmailEnabled;
    }

    public static Member register(
            Long id, String googleSubject, String email, String displayName, String profileImageUrl) {
        if (googleSubject == null || googleSubject.isBlank() || email == null || email.isBlank()) {
            throw new InvalidGoogleIdentityException();
        }
        // 신규 회원은 리포트 완료 이메일을 기본 수신한다.
        return new Member(id, googleSubject, email, displayName, profileImageUrl, true);
    }

    public static Member reconstitute(
            Long id,
            String googleSubject,
            String email,
            String displayName,
            String profileImageUrl,
            boolean reportEmailEnabled) {
        return new Member(id, googleSubject, email, displayName, profileImageUrl, reportEmailEnabled);
    }

    /** 리포트 완료 이메일 수신 설정을 바꾼 새 회원을 돌려준다. */
    public Member changeReportEmailEnabled(boolean enabled) {
        return new Member(id, googleSubject, email, displayName, profileImageUrl, enabled);
    }

    /** 표시 이름을 바꾼 새 회원을 돌려준다. 앞뒤 공백은 다듬어 저장한다 — 공백만 남는 이름은 화면에서 빈 칸으로 보이고 아바타 이니셜도 만들 수 없다. */
    public Member changeDisplayName(String newDisplayName) {
        String trimmed = newDisplayName == null ? "" : newDisplayName.trim();
        if (trimmed.isEmpty() || trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new InvalidDisplayNameException();
        }
        return new Member(id, googleSubject, email, trimmed, profileImageUrl, reportEmailEnabled);
    }

    /**
     * 탈퇴한 회원이 남길 google_subject. UNIQUE 인 원래 값을 비켜 줘야 같은 구글 계정이 새 회원으로 다시 가입할 수 있고, 그대로 두면 재로그인 때 탈퇴한 계정이 데이터째 되살아난다.
     * 회원 ID(TSID)가 붙어 있어 값 자체는 서로 겹치지 않는다.
     */
    public static String withdrawnGoogleSubject(Long memberId) {
        return WITHDRAWN_GOOGLE_SUBJECT_PREFIX + memberId;
    }

    public Long id() {
        return id;
    }

    public String googleSubject() {
        return googleSubject;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public String profileImageUrl() {
        return profileImageUrl;
    }

    public boolean reportEmailEnabled() {
        return reportEmailEnabled;
    }
}
