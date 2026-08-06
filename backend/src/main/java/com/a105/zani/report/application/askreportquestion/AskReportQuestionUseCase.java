package com.a105.zani.report.application.askreportquestion;

/** 리포트에서 드래그한 곳에 대한 질문에 답한다(S15P11A105-259). */
public interface AskReportQuestionUseCase {

    AskReportQuestionResult ask(AskReportQuestionCommand command);
}
