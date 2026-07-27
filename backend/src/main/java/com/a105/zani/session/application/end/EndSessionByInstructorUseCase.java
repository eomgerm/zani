package com.a105.zani.session.application.end;

/** 강사의 명시적 수업 종료. 요청자가 이 세션의 강사인지 확인한 뒤 종료 단일 경로에 위임한다. */
public interface EndSessionByInstructorUseCase {

    EndSessionResult endByInstructor(EndSessionByInstructorCommand command);
}
