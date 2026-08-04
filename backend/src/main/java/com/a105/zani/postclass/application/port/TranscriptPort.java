package com.a105.zani.postclass.application.port;

import java.time.Instant;
import java.util.Optional;

import com.a105.zani.postclass.application.exception.TranscriptDocumentInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptStoreUnavailableException;
import com.a105.zani.postclass.domain.model.TranscriptDocument;

/**
 * 최종 전사 저장소({@code transcripts}, S15P11A105-247).
 *
 * <p>{@code UK_TRANSCRIPTS_SESSION} 때문에 세션당 한 행이다. 그래서 이 포트에는 "추가" 가 없고 {@link #save} 하나뿐이다 — 같은 세션을 다시 조립하면 새 행이 생기는 게
 * 아니라 문서가 교체된다.
 */
public interface TranscriptPort {

    /**
     * 세션의 전사를 저장한다. 이미 있으면 문서를 교체한다.
     *
     * <p>교체가 안전한 이유는 조립이 결정적이기 때문이다. 같은 체크포인트에서 조립하면 같은 문서가 나오므로 재실행이 내용을 바꾸지 않는다. 조립이 달라졌다면 그것은 체크포인트가 달라진 것이고, 그 경우 새
     * 결과가 정본이다.
     *
     * @throws TranscriptStoreUnavailableException 저장소에 쓸 수 없음(재시도 가능)
     * @throws TranscriptDocumentInvalidException 문서를 저장 형태로 옮길 수 없음(재시도 불가)
     */
    void save(Long sessionId, TranscriptDocument document, Instant now);

    /**
     * 세션의 전사를 읽는다.
     *
     * <p>저장된 문서를 해석할 수 없을 때 빈 값이 아니라 예외다. 빈 값으로 돌려주면 "전사가 없다" 로 오인돼 재조립이 일어나고, 읽지 못한 옛 문서를 덮어쓴다.
     *
     * @throws TranscriptStoreUnavailableException 저장소를 읽을 수 없음(재시도 가능)
     * @throws TranscriptDocumentInvalidException 저장된 문서가 현재 계약과 다름(재시도 불가)
     */
    Optional<TranscriptDocument> findBySessionId(Long sessionId);
}
