package com.a105.zani.postclass.domain.model;

import java.util.List;

/**
 * {@code transcripts.transcript_document} 에 저장하는 문서(S15P11A105-247).
 *
 * <p><b>화자별 배열이 아니라 전체 시간순 평면 세그먼트다.</b> 하류 8건이 이 결과를 읽는데, 대부분은 "이 시각에 누가 무엇을 말했나" 를 묻는다. 화자별로 중첩하면 모든 소비자가 병합 정렬을 다시
 * 구현해야 하고, 구현이 갈리면 같은 전사에서 다른 타임라인이 나온다. 화자별 묶음이 필요한 쪽은 평면에서 그룹핑하면 되지만 반대는 비싸다.
 *
 * <p><b>시각은 정수 밀리초다.</b> {@code double} 초로 두지 않는다 — GMS 응답의 해상도가 파일마다 다르고, 세 겹(파일·청크·세그먼트)을 더하면서 부동소수 오차가 누적된다. 어차피 밀리초
 * 아래는 의미가 없다.
 *
 * <p><b>모든 시각은 수업 타임라인 기준 절대값이다.</b> 청크 기준 상대값은 {@code postclass_transcription_chunks.result_document} 에 그대로 남아 있다. 오프셋
 * 계산이 잘못됐음을 나중에 알게 되면 원본 없이 조립만 다시 할 수 있다.
 *
 * @param schemaVersion 문서 형태 판(version). 소비자가 형태 변경을 감지할 수 있게 항상 적는다
 * @param language 전사 요청 언어
 * @param partial 일부 구간이 빠진 전사인가. <b>MVP 는 {@code false} 만 만든다</b> — {@code true} 를 소비하는 계약이 하류에 아직 없어, 불완전한 전사는 저장하지 않고
 *     파이프라인 실패로 처리한다. 필드를 미리 두는 이유는 소비자가 지금부터 이 값을 확인하도록 만들기 위해서다
 * @param segments 시간순으로 정렬된 전체 세그먼트. 발화가 없었으면 빈 목록
 */
public record TranscriptDocument(
        int schemaVersion, String language, boolean partial, List<TranscriptDocumentSegment> segments) {

    /** 현재 문서 판. 형태를 바꿀 때 올린다. */
    public static final int SCHEMA_VERSION = 1;

    public TranscriptDocument {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** 모든 청크가 성공 또는 명시적 스킵으로 끝난 완전한 전사. */
    public static TranscriptDocument complete(String language, List<TranscriptDocumentSegment> segments) {
        return new TranscriptDocument(SCHEMA_VERSION, language, false, segments);
    }
}
