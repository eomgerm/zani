package com.a105.zani.member.domain.model;

import com.a105.zani.member.domain.exception.InvalidGoogleIdentityException;

public class Member {

    private final Long id;
    private final String googleSubject;
    private final String email;
    private final String displayName;
    private final String profileImageUrl;

    private Member(Long id, String googleSubject, String email, String displayName, String profileImageUrl) {
        this.id = id;
        this.googleSubject = googleSubject;
        this.email = email;
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
    }

    public static Member register(
            Long id, String googleSubject, String email, String displayName, String profileImageUrl) {
        if (googleSubject == null || googleSubject.isBlank() || email == null || email.isBlank()) {
            throw new InvalidGoogleIdentityException();
        }
        return new Member(id, googleSubject, email, displayName, profileImageUrl);
    }

    public static Member reconstitute(
            Long id, String googleSubject, String email, String displayName, String profileImageUrl) {
        return new Member(id, googleSubject, email, displayName, profileImageUrl);
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
}
