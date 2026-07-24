package com.a105.zani.member.application.resolve;

public record ResolveGoogleMemberCommand(
        String googleSubject, String email, String displayName, String profileImageUrl) {}
