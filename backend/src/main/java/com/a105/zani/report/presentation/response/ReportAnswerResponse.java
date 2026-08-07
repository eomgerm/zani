package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.askreportquestion.AskReportQuestionResult;
import com.a105.zani.report.application.port.AnswerCitation;

@Schema(description = "드래그한 곳에 대한 답변")
public record ReportAnswerResponse(
        @Schema(description = "답변 본문", example = "충돌이 나면 배열의 다른 빈 자리를 찾아 저장하는 방식입니다.")
        String answer,

        @Schema(description = "근거가 된 발화. 누르면 영상이 그 시각으로 이동한다. 근거가 없으면 빈 배열")
        List<Citation> citations,

        @Schema(description = "이 수업에서 다룬 내용으로 답했는가. false 면 화면이 \"이 수업에서 다루지 않았어요\" 로 읽는다", example = "true")
        boolean grounded) {

    @Schema(description = "근거가 된 발화 한 줄")
    public record Citation(
            @Schema(description = "발화 시작 시각(ms)", example = "98000")
            long offsetMs,

            @Schema(description = "그 발화의 실제 전사 문장. 모델이 쓴 문장이 아니라 서버가 전사에서 채운 값이다", example = "체이닝과 개방주소법이 있습니다.")
            String quote) {}

    public static ReportAnswerResponse from(AskReportQuestionResult result) {
        return new ReportAnswerResponse(
                result.answer(),
                result.citations().stream()
                        .map(ReportAnswerResponse::toCitation)
                        .toList(),
                result.grounded());
    }

    private static Citation toCitation(AnswerCitation citation) {
        return new Citation(citation.offsetMs(), citation.quote());
    }
}
