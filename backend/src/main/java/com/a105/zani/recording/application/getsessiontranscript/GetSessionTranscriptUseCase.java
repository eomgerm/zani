package com.a105.zani.recording.application.getsessiontranscript;

import java.util.Optional;

/** 세션의 병합 전사를 돌려준다. {@code transcripts} 는 {@code recording} 도메인이 소유하므로, 사후 분석은 이 계약으로만 전사를 읽는다. */
public interface GetSessionTranscriptUseCase {

    Optional<GetSessionTranscriptResult> get(GetSessionTranscriptQuery query);
}
