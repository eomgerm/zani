package com.a105.zani.session.application.moderation;

/**
 * 강사가 학생을 강제 음소거한다.
 *
 * <p><b>해제는 없다.</b> 강사가 남의 마이크를 켤 수 있으면 본인 모르게 소리가 나가기 시작한다. 해제는 학생 본인만 한다(FRD §10.5).
 */
public interface MuteParticipantUseCase {

    MuteParticipantResult mute(MuteParticipantCommand command);
}
