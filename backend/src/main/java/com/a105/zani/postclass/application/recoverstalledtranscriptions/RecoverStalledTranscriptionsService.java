package com.a105.zani.postclass.application.recoverstalledtranscriptions;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.postclass.application.port.PipelineJobPort;

/** 고아 전사 작업 복구. 판정은 저장소 조건 한 줄이고 여기서는 기록만 남긴다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoverStalledTranscriptionsService implements RecoverStalledTranscriptionsUseCase {

    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;

    @Override
    public int recover() {
        int recovered = pipelineJobPort.requeueStalledTranscriptions(clock.instant());
        if (recovered > 0) {
            // 조용히 넘기면 "재기동 뒤에 왜 전사가 다시 돌았나" 를 설명할 근거가 없다. 0 건은 정상이라 남기지 않는다.
            log.warn("Requeued {} transcription job(s) left behind by a stopped worker", recovered);
        }
        return recovered;
    }
}
