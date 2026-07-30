package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;

/**
 * 30분 비활성 초안의 자동 확정.
 *
 * <p>대상을 고른 시점과 확정하는 시점 사이에 강사가 `작성 완료`를 눌렀거나 다시 입력했을 수 있다. 그래서 상태를 다시 읽어 판단하지 않고 조건부 전환에 맡긴다 — 이미 확정됐거나 그 사이에 입력이 있었으면
 * 전환이 일어나지 않고 {@code false} 가 돌아온다.
 */
@Service
@RequiredArgsConstructor
public class FinalizeInactiveNoteService implements FinalizeInactiveNoteUseCase {

    private final InstructorNoteRepository instructorNoteRepository;
    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;

    @Override
    @Transactional
    public boolean finalizeInactiveNote(Long sessionId, Instant editedBefore) {
        Instant now = clock.instant();
        if (!instructorNoteRepository.finalizeIfStillInactive(sessionId, editedBefore, now)) {
            return false;
        }
        // 확정과 같은 트랜잭션에서 남긴다(NOTE-004). 확정만 커밋되고 작업이 없으면 그 수업의 분석은 영원히 시작되지 않는다.
        pipelineJobPort.enqueue(sessionId, now);
        return true;
    }
}
