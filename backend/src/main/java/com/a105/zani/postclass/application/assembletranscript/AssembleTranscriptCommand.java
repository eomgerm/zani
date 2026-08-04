package com.a105.zani.postclass.application.assembletranscript;

import java.util.List;

import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionTrack;

/**
 * 조립 입력(S15P11A105-247).
 *
 * <p>이 명령이 저장소를 다시 읽지 않고 데이터를 그대로 받는 이유는 검증 때문이다. 조립은 "받은 것을 합쳐도 되는가" 를 판정하는 단계이고, 판정 대상을 스스로 조회하면 오케스트레이션이 본 것과 조립이 본
 * 것이 달라질 수 있다. 그 차이가 곧 시간축이 밀린 전사다.
 *
 * @param sessionId 대상 세션. 모든 청크가 이 세션의 것이어야 한다
 * @param language 전사 요청 언어({@code gms.transcribe-language})
 * @param tracks 전사 대상 트랙 파일 목록. 청크가 가리키는 파일이 모두 여기 있어야 한다
 * @param chunks 이 세션의 체크포인트 전체. 하나라도 종결되지 않았거나 실패했으면 조립하지 않는다
 */
public record AssembleTranscriptCommand(
        Long sessionId, String language, List<TranscriptionTrack> tracks, List<TranscriptionChunk> chunks) {

    public AssembleTranscriptCommand {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
