package com.a105.zani.session.application.attendance;

/**
 * 실제 LiveKit 입·이탈 시각을 출석 기록으로 남긴다. 미디어 서버 webhook 을 받는 쪽이 이 UseCase 로만 세션 도메인에 들어온다.
 *
 * <p>입장 API 호출 시각({@code first_joined_at})과 구분해서 기록한다. 프리조인 화면만 보고 나간 학생을 출석으로 세지 않으려면 두 사실이 따로 남아 있어야 한다.
 */
public interface RecordMediaAttendanceUseCase {

    void record(RecordMediaAttendanceCommand command);
}
