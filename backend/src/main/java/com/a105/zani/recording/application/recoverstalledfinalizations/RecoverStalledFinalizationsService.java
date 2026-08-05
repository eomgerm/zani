package com.a105.zani.recording.application.recoverstalledfinalizations;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;

/** 고아 녹화 병합 작업 복구. 실행 중이던 산출물은 폐기하고 원본부터 다시 병합한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoverStalledFinalizationsService implements RecoverStalledFinalizationsUseCase {

    private final RecordingFinalizationJobPort jobPort;
    private final Clock clock;

    @Override
    public int recover() {
        int recovered = jobPort.requeueRunningJobs(clock.instant());
        if (recovered > 0) {
            log.warn("Requeued {} recording finalization job(s) interrupted by application restart", recovered);
        }
        return recovered;
    }
}
