package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.exception.TranscriptionChunkBoundaryMismatchException;
import com.a105.zani.postclass.application.exception.TranscriptionChunkStoreUnavailableException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionChunkPort;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.postclass.infrastructure.persistence.entity.PostClassTranscriptionChunkJpaEntity;
import com.a105.zani.postclass.infrastructure.persistence.repository.PostClassTranscriptionChunkJpaRepository;

/**
 * 전사 청크 체크포인트 영속 어댑터(S15P11A105-247).
 *
 * <p>등록은 INSERT IGNORE 로 {@code (recording_file_id, chunk_index)} UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수하고, 그 뒤 기록된 경계와 대조한다. 선점은
 * 조건부 UPDATE 한 문장이며 실행권 토큰을 돌려준다. 모든 기록 호출은 그 토큰을 조건에 넣어 lease 를 잃은 늦은 쓰기를 막는다.
 *
 * <p>각 메서드는 자기 트랜잭션으로 짧게 끝난다. GMS 호출은 이 어댑터 밖에서 일어나므로 호출 동안 행 잠금이 유지되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostClassTranscriptionChunkPersistenceAdapter implements TranscriptionChunkPort {

    /** last_error 컬럼 길이(V13). */
    private static final int MAX_ERROR_LENGTH = 500;

    private static final TypeReference<List<StoredSegment>> SEGMENT_LIST = new TypeReference<>() {};

    private final PostClassTranscriptionChunkJpaRepository repository;

    // 컨텍스트에 공용 ObjectMapper 빈이 없어 결과 문서 직렬화 전용으로 어댑터가 직접 소유한다
    // (RecordingOutboxPersistenceAdapter 와 같은 방식. record 직렬화는 Jackson 기본 지원).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public List<TranscriptionChunk> registerAll(
            Long sessionId, Long recordingFileId, List<AudioChunk> chunks, Instant now) {
        try {
            // 삽입보다 조회가 먼저다. INSERT IGNORE 뒤에 대조하면 새로 늘어난 청크가 이미 들어가 있어
            // 개수가 저절로 맞고, "원본이 길어져 청크가 늘었다" 를 잡을 수 없다.
            List<TranscriptionChunk> existing = readOrdered(recordingFileId);
            if (!existing.isEmpty()) {
                verifyBoundaries(recordingFileId, chunks, existing);
                return existing;
            }
            for (AudioChunk chunk : chunks) {
                repository.insertIgnore(
                        TsidGenerator.generate(),
                        sessionId,
                        recordingFileId,
                        chunk.index(),
                        chunk.sourceStartMs(),
                        chunk.sourceEndMs(),
                        TranscriptionChunkStatus.PENDING.name(),
                        now);
            }
            // 삽입 후에 한 번 더 본다. 같은 원본을 두 실행이 동시에 등록하면 UNIQUE 때문에 행마다 먼저
            // 쓴 쪽이 남는데, 둘의 경계가 다르면 섞인 시간축이 된다. 여기서 끊어 되돌린다.
            List<TranscriptionChunk> stored = readOrdered(recordingFileId);
            verifyBoundaries(recordingFileId, chunks, stored);
            return stored;
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    private List<TranscriptionChunk> readOrdered(Long recordingFileId) {
        return repository.findByRecordingFileIdOrderByChunkIndexAsc(recordingFileId).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 새로 자른 청크가 기록된 체크포인트와 같은지 대조한다.
     *
     * <p>{@code INSERT IGNORE} 는 충돌을 조용히 넘긴다. 재시도할 때는 임시 파일이 없어 원본을 다시 자르는데, 그 사이 청크 길이 설정이 바뀌었거나 ffmpeg 동작이 달라지면 새 청크의
     * 구간이 저장된 값과 어긋난다. 그대로 진행하면 <b>DB 의 옛 오프셋 + 새 청크의 상대 시각</b>이라는 잘못된 조합으로 절대 시각이 만들어진다.
     *
     * <p>개수·순번·시작·종료를 모두 본다. 하나라도 다르면 재시도 불가로 끊는다 — 같은 설정으로 다시 잘라도 같은 결과이므로 재시도는 예산만 태우고, 왜 달라졌는지는 사람이 봐야 한다.
     */
    private void verifyBoundaries(Long recordingFileId, List<AudioChunk> fresh, List<TranscriptionChunk> stored) {
        if (fresh.size() != stored.size()) {
            log.error(
                    "Re-split produced a different chunk count: recordingFileId={} fresh={} stored={}",
                    recordingFileId,
                    fresh.size(),
                    stored.size());
            throw new TranscriptionChunkBoundaryMismatchException();
        }
        for (int index = 0; index < fresh.size(); index++) {
            AudioChunk freshChunk = fresh.get(index);
            TranscriptionChunk storedChunk = stored.get(index);
            if (freshChunk.index() != storedChunk.chunkIndex()
                    || freshChunk.sourceStartMs() != storedChunk.sourceStartMs()
                    || freshChunk.sourceEndMs() != storedChunk.sourceEndMs()) {
                log.error(
                        "Re-split boundary disagrees with the checkpoint: recordingFileId={} chunkIndex={}"
                                + " freshStartMs={} freshEndMs={} storedStartMs={} storedEndMs={}",
                        recordingFileId,
                        freshChunk.index(),
                        freshChunk.sourceStartMs(),
                        freshChunk.sourceEndMs(),
                        storedChunk.sourceStartMs(),
                        storedChunk.sourceEndMs());
                throw new TranscriptionChunkBoundaryMismatchException();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<TranscriptionChunk> findClaimable(Long recordingFileId, Instant now, int limit) {
        try {
            return repository.findClaimable(recordingFileId, now, limit).stream()
                    .map(this::toDomain)
                    .toList();
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public OptionalInt tryClaim(Long chunkId, Instant leaseUntil, Instant now) {
        try {
            if (repository.claim(chunkId, leaseUntil, now) != 1) {
                return OptionalInt.empty();
            }
            // 갱신된 값을 다시 읽는다. 조회 시점의 attemptCount + 1 로 계산하면, 그 사이 lease 만료로 다른
            // 실행이 회수했다가 우리가 또 회수한 경우에 실제 값과 어긋난다. 벌크 UPDATE 가 영속성 컨텍스트를
            // 비우므로 이 조회는 DB 를 다시 본다.
            return repository
                    .findById(chunkId)
                    .map(entity -> OptionalInt.of(entity.getAttemptCount()))
                    .orElseGet(OptionalInt::empty);
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public boolean markSucceeded(Long chunkId, int fencingToken, List<TranscriptSegment> segments, Instant now) {
        return applyResult(chunkId, fencingToken, TranscriptionChunkStatus.SUCCEEDED, toDocument(segments), now);
    }

    @Override
    @Transactional
    public boolean markSkippedSilent(Long chunkId, int fencingToken, Instant now) {
        // 결과 문서를 비워 둔다. 호출하지 않았으므로 담을 세그먼트가 없다 — 빈 배열을 넣으면
        // "호출했는데 발화가 없었다" 와 구분되지 않는다. 단계가 그 구분을 갖는다.
        return applyResult(chunkId, fencingToken, TranscriptionChunkStatus.SKIPPED_SILENT, null, now);
    }

    private boolean applyResult(
            Long chunkId, int fencingToken, TranscriptionChunkStatus status, String document, Instant now) {
        try {
            return checkFencing(chunkId, repository.markResult(chunkId, fencingToken, status, document, now));
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public boolean markRetry(Long chunkId, int fencingToken, String error, Instant nextAttemptAt, Instant now) {
        try {
            return checkFencing(
                    chunkId, repository.markRetry(chunkId, fencingToken, truncate(error), nextAttemptAt, now));
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public boolean markFailed(Long chunkId, int fencingToken, String error, Instant now) {
        try {
            return checkFencing(chunkId, repository.markFailed(chunkId, fencingToken, truncate(error), now));
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    /**
     * 갱신 행 수를 확인한다. 0 이면 실행권을 잃은 것이다.
     *
     * <p>반환값을 무시하면 존재하지 않는 행이나 실행권 상실이 성공처럼 넘어간다. 실행권을 잃은 쪽이 자기 결과를 버리는 것이 fencing 의 전부이므로 여기서 반드시 갈라야 한다.
     */
    private boolean checkFencing(Long chunkId, int updated) {
        if (updated == 1) {
            return true;
        }
        log.warn("Discarding a transcription result that lost its lease: chunkId={}", chunkId);
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TranscriptionChunk> findAllBySessionId(Long sessionId) {
        try {
            return repository.findBySessionIdOrderByRecordingFileIdAscChunkIndexAsc(sessionId).stream()
                    .map(this::toDomain)
                    .toList();
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public void deleteBySessionId(Long sessionId) {
        try {
            repository.deleteBySessionId(sessionId);
        } catch (DataAccessException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    private TranscriptionChunk toDomain(PostClassTranscriptionChunkJpaEntity entity) {
        return new TranscriptionChunk(
                entity.getId(),
                entity.getSessionId(),
                entity.getRecordingFileId(),
                entity.getChunkIndex(),
                entity.getStartOffsetMs(),
                entity.getEndOffsetMs(),
                status(entity.getStatus()),
                entity.getAttemptCount(),
                entity.getLeaseUntil(),
                entity.getNextAttemptAt(),
                toSegments(entity.getResultDocument()));
    }

    /**
     * 저장된 단계 문자열을 enum 으로 되돌린다. 알 수 없는 값은 {@code FAILED} 로 본다.
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는 이유는 {@code RecordingPersistenceMapper} 와 같다 — 단계 이름을 바꾸거나 롤백된 배포가 옛 이름을 남기면 읽는 순간
     * 예외가 올라와 그 세션의 전사가 통째로 막힌다. 다만 여기서는 {@code null} 로 둘 수 없다(단계가 없는 청크는 의미가 없다). 처리 대상에서 빼는 것이 안전한 쪽이라 {@code FAILED}
     * 로 본다 — 조립이 그 세션을 미완료로 판정하고 사람이 보게 된다.
     */
    private TranscriptionChunkStatus status(String stored) {
        try {
            return TranscriptionChunkStatus.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            log.error("Unknown transcription chunk status stored: {}", stored);
            return TranscriptionChunkStatus.FAILED;
        }
    }

    private String toDocument(List<TranscriptSegment> segments) {
        List<StoredSegment> stored = new ArrayList<>();
        for (TranscriptSegment segment : segments == null ? List.<TranscriptSegment>of() : segments) {
            stored.add(new StoredSegment(
                    segment.startMs(), segment.endMs(), segment.text(), segment.avgLogprob(), segment.noSpeechProb()));
        }
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException exception) {
            // 직렬화가 실패하면 결과를 잃는다. 저장소 접근 실패와 같은 등급으로 올려 재시도되게 한다.
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    private List<TranscriptSegment> toSegments(String document) {
        if (document == null || document.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(document, SEGMENT_LIST).stream()
                    .map(stored -> new TranscriptSegment(
                            stored.startMs(),
                            stored.endMs(),
                            stored.text(),
                            stored.avgLogprob(),
                            stored.noSpeechProb()))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new TranscriptionChunkStoreUnavailableException(exception);
        }
    }

    /**
     * last_error 컬럼 길이에 맞춘다.
     *
     * <p>자르지 않으면 긴 메시지가 들어올 때 UPDATE 가 통째로 실패하는데, 그러면 실패를 기록하려다 실패해 청크가 재시도 대기에도 최종 실패에도 들어가지 못하고 {@code PROCESSING} 으로
     * 남는다. lease 만료로 회수되긴 하지만 같은 실패를 상한까지 반복한다.
     */
    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    /** {@code result_document} 에 저장하는 형태. 시각은 청크 기준 상대값이다. */
    private record StoredSegment(long startMs, long endMs, String text, double avgLogprob, double noSpeechProb) {}
}
