package com.a105.zani.member.application.resolve;

public record ResolveGoogleMemberResult(
        Long memberId, String email, String displayName, String profileImageUrl, boolean newMember) {}
