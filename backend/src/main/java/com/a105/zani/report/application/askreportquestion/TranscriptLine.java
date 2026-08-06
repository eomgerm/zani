package com.a105.zani.report.application.askreportquestion;

/**
 * 시간창 안의 발화 한 줄.
 *
 * <p>화자를 <b>참가자 id 그대로</b> 들고 있는 것이 이 타입의 요점이다. 별칭 치환은 GMS 로 나가는 경계에서 서비스가 한다 — 저장 관심사가 아니라 익명화 규칙(GMS 가이드 §9)이라, 조회
 * 어댑터가 미리 바꿔 두면 규칙이 인프라에 숨는다.
 *
 * <p>시각은 밀리초다. 화면용 전사({@code SessionTranscriptQuery.segments})가 초로 낮추는 것과 다른데, 인용 검증이 ms 로 판정하기 때문이다. 여기서 초로 낮추면 모델이 준
 * 오프셋을 실제 발화에 맞출 수 없다.
 *
 * @param sessionParticipantId 참가자 행이 사라진 전사(회원 탈퇴 등)에서는 {@code null}
 */
public record TranscriptLine(long startOffsetMs, long endOffsetMs, Long sessionParticipantId, String text) {}
