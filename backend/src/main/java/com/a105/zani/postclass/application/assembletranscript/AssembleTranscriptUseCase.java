package com.a105.zani.postclass.application.assembletranscript;

import com.a105.zani.postclass.application.exception.TranscriptAssemblyInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptDocumentInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptIncompleteException;
import com.a105.zani.postclass.application.exception.TranscriptNotReadyException;
import com.a105.zani.postclass.application.exception.TranscriptStoreUnavailableException;

/**
 * 청크 결과를 하나의 전사 문서로 조립해 저장한다(S15P11A105-247).
 *
 * <p>조립이 하는 일은 <b>시간축 합성</b>이다. 절대 시각은 세 겹의 합이다.
 *
 * <pre>
 * 절대 시각 = 파일의 수업 기준 시작(recording_files.started_offset_ms)
 *          + 청크의 파일 기준 시작(FFmpeg segment CSV)
 *          + 세그먼트의 청크 기준 시작(GMS 응답)
 * </pre>
 *
 * <p>세 겹이라 검증 없이 더하면 어긋난 것을 알 수 없다. 그래서 조립은 더하기 전에 거절할 이유를 먼저 찾는다 — 끝나지 않은 청크, 영구 실패한 청크, 다른 세션의 청크, 중복 청크, 화자·트랙
 * 종류·시각이 없는 파일, 청크 밖에서 시작하는 세그먼트, 청크 길이를 크게 넘는 세그먼트.
 *
 * <p><b>영구 실패 정책.</b> 모든 청크가 {@code SUCCEEDED} 또는 {@code SKIPPED_SILENT} 일 때만 저장한다. {@code FAILED} 가 하나라도 있으면 저장하지 않는다
 * — 호출자는 {@code ANALYZING} 으로 넘기지 않고 파이프라인 실패로 처리한다.
 *
 * <p><b>무음 환각 필터(S15P11A105-306).</b> 조립은 {@code noSpeechProb} 가 임곗값 이상인 세그먼트를 최종 문서에서 뺀다. 단 <b>앞뒤로 시각이 맞물린 실제 발화가 있으면
 * 남긴다</b> — {@code no_speech_prob} 는 세그먼트가 아니라 30초 디코딩 창의 값이라, 창 경계를 넘어간 문장의 뒷부분이 무음 창의 값을 물려받는 일이 실제로 있다. 규칙과 실측 근거는
 * {@link com.a105.zani.postclass.application.port.TranscriptFilterSettings} 에 있다. 이 단계에서 거르는 것이므로:
 *
 * <ul>
 *   <li>GMS 응답과 {@code postclass_transcription_chunks.result_document} 는 <b>바뀌지 않는다</b>. 임곗값을 고쳐 재조립하면 GMS 없이 결과가 달라진다
 *   <li>필터는 <b>실패가 아니다</b>. 전부 걸러져 세그먼트가 0개가 되어도 정상 저장이고 {@code partial} 은 여전히 {@code false} 다 — {@code partial=true} 는
 *       "구간이 빠진 전사" 를 뜻하고 소비 계약이 하류에 없다
 *   <li>남은 세그먼트는 <b>손대지 않는다</b>. 시각·화자·식별자·확률값 그대로이고, 빠진 자리를 메우거나 주변 시각을 당기지 않는다. 문서 형태와 {@code schemaVersion} 도 그대로다
 * </ul>
 *
 * <p><b>예외는 재시도 여부로 나뉜다.</b> 호출자가 {@code RecordPipelineFailureCommand.retryable} 에 그대로 옮길 수 있게 만든 구분이다. 하나로 합치면 두 방향으로
 * 틀린다 — 영구 실패를 상한까지 재시도해 예산을 태우거나, 아직 처리 중인 세션을 너무 일찍 최종 실패로 굳혀 남은 청크의 결과를 버린다.
 *
 * <table border="1">
 *   <caption>실패 분류</caption>
 *   <tr><th>예외</th><th>retryable</th><th>뜻</th></tr>
 *   <tr><td>{@link TranscriptNotReadyException}</td><td>{@code true}</td><td>아직 종결되지 않은 청크가 있다</td></tr>
 *   <tr><td>{@link TranscriptStoreUnavailableException}</td><td>{@code true}</td><td>저장소 접근 실패</td></tr>
 *   <tr><td>{@link TranscriptIncompleteException}</td><td>{@code false}</td><td>영구 실패 청크가 있다</td></tr>
 *   <tr><td>{@link TranscriptAssemblyInvalidException}</td><td>{@code false}</td><td>타임라인을 만들 수 없는 데이터</td></tr>
 *   <tr><td>{@link TranscriptDocumentInvalidException}</td><td>{@code false}</td><td>문서 저장 계약 위반</td></tr>
 * </table>
 */
public interface AssembleTranscriptUseCase {

    /**
     * @throws TranscriptNotReadyException 아직 종결되지 않은 청크가 있음(재시도 가능)
     * @throws TranscriptIncompleteException 영구 실패한 청크가 있음(재시도 불가, 파이프라인 최종 실패)
     * @throws TranscriptAssemblyInvalidException 받은 데이터로 타임라인을 만들 수 없음(재시도 불가)
     * @throws TranscriptDocumentInvalidException 만든 문서를 저장 형태로 옮길 수 없음(재시도 불가)
     * @throws TranscriptStoreUnavailableException 저장소에 쓸 수 없음(재시도 가능)
     */
    AssembleTranscriptResult assemble(AssembleTranscriptCommand command);
}
