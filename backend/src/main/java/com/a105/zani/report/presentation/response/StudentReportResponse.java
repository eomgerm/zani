package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getstudentreport.GetStudentReportResult;

@Schema(description = "종료된 수업의 학생 본인 학습 리포트. 복습 클립이 쓰는 재생 정보를 함께 담는다")
public record StudentReportResponse(
        Activity activity,
        String participationSummary,
        List<Recommendation> recommendations,

        @Schema(
                description = "녹화 재생 주소. 자격이 담긴 단기 주소라 그대로 <video src> 에 넣을 수 있다."
                        + " 최종 MP4 병합이 아직이면 null 이며 오류가 아니다 — 리포트의 나머지는 그대로 쓸 수 있다.",
                example = "http://localhost:18080/api/v1/sessions/42/media?expires=1786500000&token=abc",
                nullable = true)
        String recordingUrl,

        @Schema(description = "수업 길이(초). 종료 시각을 저장하기 전에 끝난 과거 세션이면 0 이다", example = "4440")
        long durationSeconds,

        @Schema(description = "실명 화자 전사. 시작 시각 오름차순이며, 전사가 아직 없으면 빈 배열이다")
        List<TranscriptSegment> transcript,

        @Schema(description = "초기 재생 위치(초). 딥링크 진입 위치 전용이며 추천 카드의 이동 목표는 각 추천의 startSeconds 다", example = "0")
        long seekTimestamp) {

    /**
     * 본인 활동 집계. 다른 학생과 견주는 값이 아니며 하나의 점수로 합치지 않는다(REPORT-S-010).
     *
     * <p>앞의 셋은 서버가 행을 센 값이라 항상 있다. {@code questionCount} 만 모델이 판단한 저장 값이라 비어 있을 수 있다 — 공개 채팅에는 질문만 있지 않아서 행을 셀 수 없다.
     */
    public record Activity(
            @Schema(description = "공개 채팅으로 남긴 발화 수", example = "4")
            long publicChatCount,

            @Schema(description = "확인 프롬프트에 '헷갈려요' 로 답한 횟수", example = "1")
            long confusedCount,

            @Schema(description = "확인 프롬프트에 '놓쳤어요' 로 답한 횟수", example = "0")
            long missedCount,

            @Schema(
                    description = "AI 가 공개 채팅에서 질문인 발화만 세어 판단한 질문 수."
                            + " 분석이 값을 내지 못했으면 null 이며 0 이 아니다 — 0 은 질문을 안 했다는 뜻이다.",
                    example = "2",
                    nullable = true)
            Integer questionCount) {}

    public record Recommendation(
            /*
             * TSID 를 문자열로 내보낸다. 숫자로 내보내면 JS 의 안전 정수 범위(2^53)를 넘겨 클라이언트가 파싱하는 순간 값이 조용히 뭉개진다 —
             * 화면은 이 값을 카드 식별에만 쓰므로 문자열로 다뤄도 잃는 것이 없다.
             */
            @Schema(description = "복습 추천 식별자(TSID 문자열)", example = "1000000025011")
            String id,

            @Schema(description = "근거 유형", example = "CONFUSED")
            String recommendationType,

            @Schema(description = "다시 볼 개념", example = "useMemo 메모이제이션 패턴")
            String title,

            /*
             * 저장소 컬럼은 description 이지만 화면 계약의 이름은 reason 이다. 카드에 "왜 이걸 다시 보라는지"로 쓰이는 문장이라
             * 그 뜻이 이름에 드러나는 쪽을 경계 이름으로 삼는다.
             */
            @Schema(description = "이 추천의 근거 문장", example = "헷갈림 응답과 반복된 확인 신호가 함께 근거가 됐습니다.")
            String reason,

            @Schema(description = "다시 볼 구간 시작(초)", example = "1440")
            long startSeconds,

            @Schema(description = "다시 볼 구간 끝(초)", example = "1859")
            long endSeconds,

            @Schema(description = "우선순위. 작을수록 먼저 보여준다", example = "1")
            int priority) {

        private static Recommendation from(GetStudentReportResult.Recommendation recommendation) {
            return new Recommendation(
                    String.valueOf(recommendation.id()),
                    recommendation.recommendationType(),
                    recommendation.title(),
                    recommendation.description(),
                    recommendation.startSeconds(),
                    recommendation.endSeconds(),
                    recommendation.priority());
        }
    }

    /** 전사 한 줄. 화자는 표시 이름이며 익명 별칭을 내보내지 않는다(REPORT-S-001). */
    public record TranscriptSegment(
            @Schema(description = "발화 시작(초)", example = "922")
            long startSeconds,

            @Schema(description = "발화 끝(초)", example = "940")
            long endSeconds,

            @Schema(description = "화자 표시 이름", example = "양지훈")
            String speakerName,

            @Schema(description = "발화 내용", example = "선생님, Context 값이 바뀌면 왜 하위 전체가 리렌더되나요?")
            String text) {

        private static TranscriptSegment from(GetStudentReportResult.TranscriptSegment segment) {
            return new TranscriptSegment(
                    segment.startSeconds(), segment.endSeconds(), segment.speakerName(), segment.text());
        }
    }

    public static StudentReportResponse from(GetStudentReportResult result) {
        return new StudentReportResponse(
                new Activity(
                        result.activity().publicChatCount(),
                        result.activity().confusedCount(),
                        result.activity().missedCount(),
                        result.activity().questionCount()),
                result.participationSummary(),
                result.recommendations().stream().map(Recommendation::from).toList(),
                result.recordingUrl(),
                result.durationSeconds(),
                result.transcript().stream().map(TranscriptSegment::from).toList(),
                result.seekTimestamp());
    }
}
