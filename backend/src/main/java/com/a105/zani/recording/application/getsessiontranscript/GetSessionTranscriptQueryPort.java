package com.a105.zani.recording.application.getsessiontranscript;

import java.util.Optional;

/** 저장된 전사 문서를 읽어 온다. {@code transcripts.transcript_document} 는 JSON 컬럼이라 애그리거트가 아니라 프로젝션 조회다. */
public interface GetSessionTranscriptQueryPort {

    /** 전사가 아직 없으면 빈 값. 전사 단계가 끝나지 않은 세션과 발화가 없던 세션은 다른 상태다. */
    Optional<GetSessionTranscriptResult> findBySessionId(Long sessionId);
}
