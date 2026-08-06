package com.a105.zani.recording.application.issuethumbnailurl;

import java.util.Optional;

public interface IssueThumbnailUrlUseCase {

    /** 이 세션의 썸네일 접근 주소. 파일이 아직 없으면 비어 있다 — 열리지 않는 주소를 내주지 않는다. */
    Optional<String> issue(long sessionId);
}
