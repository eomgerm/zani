package com.a105.zani.postclass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.postclass.application.finalizenote.FinalizeInactiveNoteUseCase;
import com.a105.zani.postclass.application.finalizenote.FinalizeNoteCommand;
import com.a105.zani.postclass.application.finalizenote.FinalizeNoteUseCase;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftCommand;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftUseCase;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.postclass.domain.model.InstructorNote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수동 완료와 30분 비활성 자동 확정이 같은 순간에 들어와도 확정과 사후 처리 작업이 각각 한 번이어야 한다(FRD §16 NOTE-003·NOTE-004). 실제 MySQL 의 행 잠금과 UNIQUE 제약을
 * 거쳐야 의미가 있으므로 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p>대역으로 경합을 흉내낸 검증은 {@code FinalizeNoteServiceTest}·{@code FinalizeInactiveNoteServiceTest} 에 있다. 여기서는 두 트랜잭션을 진짜로
 * 부딪힌다.
 */
@SpringBootTest
class InstructorNoteFinalizationConcurrencyTest {

    private static final long INSTRUCTOR_ID = 9_301_010L;
    private static final long SESSION_ID = 9_301_012L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_301_013L;

    @Autowired
    private SaveNoteDraftUseCase saveNoteDraftUseCase;

    @Autowired
    private FinalizeNoteUseCase finalizeNoteUseCase;

    @Autowired
    private FinalizeInactiveNoteUseCase finalizeInactiveNoteUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cleanUpRows();
        insertMember();
        insertEndedSession();
        insertParticipant();
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void finalizesOnceAndQueuesOneJobWhenBothPathsRace() throws InterruptedException {
        givenDraft();
        // 스윕이 고를 수 있는 상태로 만든다: 마지막 입력이 비활성 기준보다 앞이어야 한다.
        Instant editedBefore = now.minus(InstructorNote.INACTIVITY_WINDOW);
        jdbcTemplate.update(
                "UPDATE instructor_notes SET last_edited_at = ? WHERE session_id = ?",
                utc(editedBefore.minusSeconds(60)),
                SESSION_ID);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> race(
                ready,
                start,
                done,
                winners,
                failure,
                () -> finalizeNoteUseCase
                        .finalizeNote(new FinalizeNoteCommand(SESSION_ID, INSTRUCTOR_ID))
                        .finalizedNow()));
        executor.submit(() -> race(
                ready,
                start,
                done,
                winners,
                failure,
                () -> finalizeInactiveNoteUseCase.finalizeInactiveNote(SESSION_ID, editedBefore)));

        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "확정 경합이 제 시간에 끝나지 않았습니다");
        executor.shutdown();

        if (failure.get() != null) {
            throw failure.get();
        }
        // 확정을 일으켰다고 보고한 쪽은 하나뿐이어야 한다. 둘이면 후속 작업이 두 번 돈다.
        assertEquals(1, winners.get());
        assertEquals(1, finalizedNoteCount());
        assertEquals(1, jobCount());
    }

    @Test
    void keepsTheFinalizationWhenADraftSaveLandsAtTheSameMoment() throws InterruptedException {
        givenDraft();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<RuntimeException> unexpected = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                finalizeNoteUseCase.finalizeNote(new FinalizeNoteCommand(SESSION_ID, INSTRUCTOR_ID));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                unexpected.compareAndSet(null, exception);
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                saveNoteDraftUseCase.save(new SaveNoteDraftCommand(SESSION_ID, INSTRUCTOR_ID, "확정과 겹친 입력"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (NoteAlreadyFinalizedException expected) {
                // 확정이 먼저 커밋됐다. 초안 저장은 덮어쓰지 않고 수정 불가로 물러난다.
            } catch (RuntimeException exception) {
                unexpected.compareAndSet(null, exception);
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "저장·확정 경합이 제 시간에 끝나지 않았습니다");
        executor.shutdown();

        if (unexpected.get() != null) {
            throw unexpected.get();
        }
        // 어느 순서로 끝나도 확정은 남아야 한다. 초안 저장이 엔티티를 통째로 덮어쓰면 확정이 DRAFT 로 되돌아가고,
        // 이미 만들어진 사후 처리 작업만 남아 편집 가능한 메모를 분석하게 된다.
        assertEquals(1, finalizedNoteCount());
        assertEquals(1, jobCount());
    }

    @Test
    void refusesToEditAfterTheRaceIsSettled() {
        givenDraft();
        finalizeNoteUseCase.finalizeNote(new FinalizeNoteCommand(SESSION_ID, INSTRUCTOR_ID));

        // 확정 후에는 수정·재생성할 수 없다(FRD §16).
        assertThrows(NoteAlreadyFinalizedException.class, this::givenDraft);
        assertEquals(1, jobCount());
    }

    private void race(
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger winners,
            AtomicReference<RuntimeException> failure,
            FinalizeAttempt attempt) {
        ready.countDown();
        try {
            start.await();
            if (attempt.run()) {
                winners.incrementAndGet();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception);
        } finally {
            done.countDown();
        }
    }

    private interface FinalizeAttempt {
        boolean run();
    }

    private void givenDraft() {
        saveNoteDraftUseCase.save(new SaveNoteDraftCommand(SESSION_ID, INSTRUCTOR_ID, "경합 대상 본문"));
    }

    private int finalizedNoteCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_notes WHERE session_id = ? AND status = 'FINALIZED'"
                        + " AND finalized_at IS NOT NULL",
                Integer.class,
                SESSION_ID);
    }

    private int jobCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pipeline_jobs WHERE session_id = ?", Integer.class, SESSION_ID);
    }

    private void insertMember() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                INSTRUCTOR_ID,
                "google-" + INSTRUCTOR_ID,
                INSTRUCTOR_ID + "@example.com",
                "확정 경합 테스트 강사",
                utc(now),
                utc(now));
    }

    private void insertEndedSession() {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "확정 경합 테스트",
                "NOTE1012",
                utc(now.minusSeconds(7_200)),
                utc(now.minusSeconds(3_600)),
                utc(now),
                utc(now));
    }

    private void insertParticipant() {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                INSTRUCTOR_PARTICIPANT_ID,
                SESSION_ID,
                INSTRUCTOR_ID,
                utc(now.minusSeconds(7_200)),
                utc(now.minusSeconds(3_600)),
                utc(now),
                utc(now));
    }

    private void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM instructor_notes WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }
}
