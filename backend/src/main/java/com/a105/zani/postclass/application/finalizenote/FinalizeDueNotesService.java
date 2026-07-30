package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;

/**
 * 30분 넘게 입력이 없는 초안을 찾아 자동 확정한다(FRD §16 NOTE-002·NOTE-003).
 *
 * <p>타이머를 따로 두지 않고 마지막 입력 시각으로 판정한다. 입력이 있을 때마다 그 시각이 갱신되니 그것이 곧 타이머 초기화이고, 만료 여부는 언제 물어도 같은 답이 나온다 — 서버가 재기동하거나 스케줄러가
 * 멈춰 있던 동안 밀린 메모도 다음 실행에서 한꺼번에 정리된다. 별도 저장소에 타이머를 두면 그 저장소와 확정 상태가 어긋날 수 있다.
 *
 * <p>한 번에 처리할 건수를 제한해 긴 트랜잭션·대량 처리를 피한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeDueNotesService implements FinalizeDueNotesUseCase {

    private static final int BATCH_SIZE = 50;

    private final InstructorNoteRepository instructorNoteRepository;
    private final FinalizeInactiveNoteUseCase finalizeInactiveNoteUseCase;
    private final Clock clock;

    /** 확정은 각 건이 독립 트랜잭션(FinalizeInactiveNoteService)이라 여기서는 트랜잭션을 걸지 않는다. 한 건 실패가 나머지를 막지 않는다. */
    @Override
    public int finalizeDueNotes() {
        Instant editedBefore = clock.instant().minus(InstructorNote.INACTIVITY_WINDOW);
        List<Long> due = instructorNoteRepository.findDueDraftSessionIds(editedBefore, BATCH_SIZE);

        int finalized = 0;
        for (Long sessionId : due) {
            try {
                if (finalizeInactiveNoteUseCase.finalizeInactiveNote(sessionId, editedBefore)) {
                    finalized++;
                }
            } catch (RuntimeException exception) {
                // 예외 객체를 함께 넘겨 스택트레이스를 남긴다. 한 건 실패의 원인을 로그만으로 추적할 수 있어야 한다.
                log.error("Failed to finalize the inactive note of session {}", sessionId, exception);
            }
        }
        if (finalized > 0) {
            log.info("Finalized {} instructor note(s) after 30 minutes without input", finalized);
        }
        return finalized;
    }
}
