package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.askreportquestion.TranscriptLine;
import com.a105.zani.report.application.askreportquestion.TranscriptWindowQueryPort;

/**
 * 리포트 질의응답이 쓸 전사 시간창을 읽는다(S15P11A105-259).
 *
 * <p>펼치는 규칙은 {@link SessionTranscriptQuery} 가 소유하고 이 클래스는 옮겨 담기만 한다 — 학생 복습 클립·강사 수업 클립 어댑터와 같은 모양이다. 규칙을 여기로 복사하면 결측
 * 처리나 정렬이 한쪽만 고쳐진다.
 *
 * <p>화자를 별칭으로 바꾸지 않고 참가자 id 를 그대로 올린다. 익명화는 GMS 로 나가는 경계(Application Service)의 규칙이라, 저장 계층이 미리 해 두면 규칙이 인프라에 숨어 다음 소비자가
 * 그것을 모른 채 실명 경로를 다시 만든다.
 */
@Component
@RequiredArgsConstructor
public class TranscriptWindowQueryAdapter implements TranscriptWindowQueryPort {

    private final SessionTranscriptQuery transcriptQuery;

    @Override
    public List<TranscriptLine> findIn(long sessionId, long fromMs, long toMs) {
        return transcriptQuery.segmentsIn(sessionId, fromMs, toMs).stream()
                .map(segment -> new TranscriptLine(
                        segment.startOffsetMs(), segment.endOffsetMs(), segment.sessionParticipantId(), segment.text()))
                .toList();
    }
}
