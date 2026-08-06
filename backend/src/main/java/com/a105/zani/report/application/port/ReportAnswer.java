package com.a105.zani.report.application.port;

import java.util.List;

/**
 * 모델이 낸 답변과, 검증을 통과한 인용.
 *
 * @param grounded 이 수업에서 다룬 내용으로 답했는가. {@code false} 면 화면이 "이 수업에서 다루지 않았어요" 로 읽는다. 지어낸 답을 그럴듯하게 내보내는 것보다 모른다고 말하는 편이
 *     낫다
 */
public record ReportAnswer(String answer, List<AnswerCitation> citations, boolean grounded) {

    public ReportAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
