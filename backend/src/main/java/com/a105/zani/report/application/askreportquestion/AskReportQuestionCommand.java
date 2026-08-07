package com.a105.zani.report.application.askreportquestion;

import java.util.List;

import com.a105.zani.report.application.port.HistoryTurn;

/**
 * 리포트에서 드래그한 곳에 대한 질문.
 *
 * <p>상태 변경 유스케이스의 입력이라 {@code Command} 다. 바뀌는 상태는 Redis 의 질문 간격뿐이고 관계형 저장은 없다 — 가이드 §6.1 의 "순수 기술 상태 변경" 이라 트랜잭션을 받지
 * 않는다.
 *
 * <p>{@code question}, {@code selectedText}, {@code history} 는 클라이언트가 보낸 값이다. 길이와 턴 수는 표현 계층이 이미 거절로 걸렀고, 여기서부터는 근거가
 * 아니라 데이터로만 다룬다.
 *
 * @param anchorStartMs 드래그한 구간의 시작 시각. 전체 요약 문단처럼 구간 밖을 짚었으면 {@code null}
 */
public record AskReportQuestionCommand(
        Long sessionId,
        Long memberId,
        String question,
        String selectedText,
        Long anchorStartMs,
        List<HistoryTurn> history) {

    public AskReportQuestionCommand {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
