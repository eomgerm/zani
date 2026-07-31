package com.a105.zani.postclass.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.finalizenote.FinalizeDueNotesUseCase;

/**
 * 30분 넘게 입력이 없는 강사 메모를 주기적으로 확정한다. 판단·확정 로직은 유스케이스가 갖고, 이 어댑터는 주기 실행만 담당한다. 스케줄러가 멈춘 동안 밀린 메모도 다음 실행에서 한꺼번에 정리된다(시각 기준
 * 판정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteInactivityScheduler {

    private final FinalizeDueNotesUseCase finalizeDueNotesUseCase;

    @Scheduled(fixedDelayString = "${postclass.note-inactivity-sweep-delay:PT1M}")
    public void sweep() {
        try {
            finalizeDueNotesUseCase.finalizeDueNotes();
        } catch (RuntimeException exception) {
            // 스케줄러는 예외를 삼켜 다음 주기를 살리므로, 여기서 스택트레이스를 남기지 않으면 원인이 사라진다.
            log.warn("Instructor note inactivity sweep failed", exception);
        }
    }
}
