package com.a105.zani.member.domain.model;

import com.a105.zani.member.domain.exception.InvalidGoogleIdentityException;

public class Member {

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
