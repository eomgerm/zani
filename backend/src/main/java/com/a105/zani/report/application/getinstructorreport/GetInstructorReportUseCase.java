package com.a105.zani.report.application.getinstructorreport;

import com.a105.zani.report.application.exception.InstructorReportNotReadyException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;

/** 종료된 수업의 강사 리포트를 여는 읽기 유스케이스. */
public interface GetInstructorReportUseCase {

    /**
     * @throws NotSessionMemberException 해당 세션의 멤버가 아님
     * @throws SessionNotFoundException 세션 없음
     * @throws SessionNotEndedException 아직 진행 중인 세션 — 리포트는 종료 후에만 만든다
     * @throws NotSessionInstructorException 멤버지만 강사가 아님
     * @throws InstructorReportNotReadyException AI 가 아직 리포트를 만들지 않았거나 공개 전
     */
    GetInstructorReportResult get(GetInstructorReportQuery query);
}
