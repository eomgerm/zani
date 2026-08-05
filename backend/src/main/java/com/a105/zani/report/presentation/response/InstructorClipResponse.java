package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getinstructorclip.GetInstructorClipResult;

@Schema(description = "종료된 수업의 강사용 수업 클립 재생 정보. 학생 리포트가 싣는 재생 정보와 같은 모양이다")
public record InstructorClipResponse(
        @Schema(
                description = "녹화 재생 주소. 자격이 담긴 단기 주소라 그대로 <video src> 에 넣을 수 있다."
                        + " 최종 MP4 병합이 아직이면 null 이며 오류가 아니다 — 전사는 그대로 쓸 수 있다.",
                example = "http://localhost:18080/api/v1/sessions/42/media?expires=1786500000&token=abc",
                nullable = true)
        String recordingUrl,

        @Schema(description = "수업 길이(초). 종료 시각을 저장하기 전에 끝난 과거 세션이면 0 이다", example = "4440")
        long durationSeconds,

        @Schema(description = "실명 화자 전사. 시작 시각 오름차순이며, 전사가 아직 없으면 빈 배열이다")
        List<TranscriptSegment> transcript,

        @Schema(description = "초기 재생 위치(초). 딥링크 진입 위치 전용이다", example = "0")
        long seekTimestamp) {

    /** 전사 한 줄. 화자는 표시 이름이며 익명 별칭을 내보내지 않는다(REPORT-S-001). */
    public record TranscriptSegment(
            @Schema(description = "발화 시작(초)", example = "922")
            long startSeconds,

            @Schema(description = "발화 끝(초)", example = "940")
            long endSeconds,

            @Schema(description = "화자 표시 이름", example = "양지훈")
            String speakerName,

            @Schema(description = "발화 내용", example = "Context 값이 바뀌면 하위 구독자만 다시 그려집니다.")
            String text) {

        private static TranscriptSegment from(GetInstructorClipResult.TranscriptSegment segment) {
            return new TranscriptSegment(
                    segment.startSeconds(), segment.endSeconds(), segment.speakerName(), segment.text());
        }
    }

    public static InstructorClipResponse from(GetInstructorClipResult result) {
        return new InstructorClipResponse(
                result.recordingUrl(),
                result.durationSeconds(),
                result.transcript().stream().map(TranscriptSegment::from).toList(),
                result.seekTimestamp());
    }
}
