package com.a105.zani.audioclip.infrastructure.buffer;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 링버퍼의 무음 패딩 틱.
 *
 * <p>음소거 중에는 Egress 가 프레임을 보내지 않아 버퍼의 바이트 수가 경과 시간보다 모자라게 된다. 짧은 주기로 부족분을 메워야 "최근 N초"가 벽시계 기준으로 정확해진다.
 */
@Component
@RequiredArgsConstructor
public class InstructorAudioSilenceScheduler {

    private final InstructorAudioBuffer buffer;

    /** 허용 오차(500ms)보다 촘촘해야 공백이 즉시 메워진다. */
    @Scheduled(fixedDelayString = "${audio-clip.silence-padding-delay:PT0.1S}")
    public void padSilence() {
        buffer.padSilence();
    }
}
