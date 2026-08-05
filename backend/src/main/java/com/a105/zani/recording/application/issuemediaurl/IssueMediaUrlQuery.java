package com.a105.zani.recording.application.issuemediaurl;

/** {@code memberId} 는 인증 주체에서만 온다. 요청 본문이나 경로로 받으면 남의 자격으로 주소를 받아 갈 수 있다. */
public record IssueMediaUrlQuery(Long sessionId, Long memberId) {}
