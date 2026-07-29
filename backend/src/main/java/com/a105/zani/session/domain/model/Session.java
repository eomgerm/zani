package com.a105.zani.session.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.a105.zani.session.domain.exception.IllegalSessionTransitionException;
import com.a105.zani.session.domain.exception.InvalidSessionTitleException;

/**
 * 수업 세션 Aggregate. 상태 전이는 모두 이 클래스의 행동을 거친다(가이드 §4).
 *
 * <pre>
 * PREPARING → LIVE → ENDING → NOTE_PENDING → ENDED
 * </pre>
 *
 * <p>전이 메서드는 모두 멱등하다. 실제로 상태를 바꿨으면 {@code true}, 이미 그 지점을 지났으면 {@code false}를 돌려준다. 재시도·중복 webhook·동시 종료 요청이 부작용을 두 번
 * 일으키지 않도록, 호출자는 반환값이 {@code true}일 때만 후속 작업(Egress 정리, 알림)을 수행한다. 뒤로 가는 전이는
 * {@link IllegalSessionTransitionException}으로 막는다.
 *
 * <p>{@code startedAt}은 실제 수업 시작 시각이라 {@code PREPARING} 동안 비어 있다. 최대 수업 시간도 이 시각부터 세므로 준비만 하고 시작하지 않은 세션에는 만료 시각이 없다.
 */
public class Session {

    public static final Duration ACTIVE_DURATION = Duration.ofHours(3);
    /** 종료 후 강사가 메모를 남길 수 있는 시간(가이드 §9). */
    public static final Duration NOTE_WINDOW = Duration.ofMinutes(30);

    private static final int TITLE_MAX_LENGTH = 100;

    private final Long id;
    private final Long instructorId;
    private final String title;
    private final String inviteCode;
    private final boolean limitedMode;
    private Instant startedAt;
    private SessionStatus status;
    private SessionAnalysisStatus analysisStatus;
    private Instant endedAt;
    private Instant noteDueAt;
    private SessionEndReason endReason;

    private Session(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            SessionStatus status,
            SessionAnalysisStatus analysisStatus,
            Instant endedAt,
            Instant noteDueAt,
            SessionEndReason endReason) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.inviteCode = inviteCode;
        this.limitedMode = limitedMode;
        this.startedAt = startedAt;
        this.status = status;
        this.analysisStatus = analysisStatus;
        this.endedAt = endedAt;
        this.noteDueAt = noteDueAt;
        this.endReason = endReason;
    }

    /** 강사가 방을 만든 직후 상태. 아직 시작 전이라 학생은 입장할 수 없고 시작 시각도 없다. */
    public static Session prepare(Long id, Long instructorId, String title, String inviteCode) {
        String trimmedTitle = title == null ? null : title.trim();
        if (trimmedTitle == null || trimmedTitle.isEmpty() || trimmedTitle.length() > TITLE_MAX_LENGTH) {
            throw new InvalidSessionTitleException();
        }
        return new Session(
                id,
                instructorId,
                trimmedTitle,
                inviteCode,
                false,
                null,
                SessionStatus.PREPARING,
                SessionAnalysisStatus.NOT_STARTED,
                null,
                null,
                null);
    }

    public static Session reconstitute(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            SessionStatus status,
            SessionAnalysisStatus analysisStatus,
            Instant endedAt,
            Instant noteDueAt,
            SessionEndReason endReason) {
        return new Session(
                id,
                instructorId,
                title,
                inviteCode,
                limitedMode,
                startedAt,
                status,
                analysisStatus,
                endedAt,
                noteDueAt,
                endReason);
    }

    /**
     * 강사 미디어와 녹화 준비가 확인된 뒤 수업을 실제로 시작한다. 이 시점부터 초대 코드가 유효해지고 최대 수업 시간이 흐르기 시작한다.
     *
     * @return 이번 호출이 시작시켰으면 true, 이미 진행 중이면 false
     */
    public boolean markLive(Instant at) {
        if (status == SessionStatus.LIVE) {
            return false;
        }
        if (status != SessionStatus.PREPARING) {
            throw new IllegalSessionTransitionException();
        }
        this.status = SessionStatus.LIVE;
        this.startedAt = at;
        return true;
    }

    /**
     * 종료 절차를 시작한다. 이 순간부터 신규 입장·토큰 발급이 막히며, 호출자는 Egress 종료와 Room 정리를 이어서 수행한다.
     *
     * @return 이번 호출이 종료를 시작시켰으면 true, 이미 종료 절차에 들어갔으면 false
     */
    public boolean beginEnding(SessionEndReason reason, Instant at) {
        if (hasStartedEnding()) {
            return false;
        }
        this.status = SessionStatus.ENDING;
        this.endReason = reason;
        this.endedAt = at;
        return true;
    }

    /**
     * Room 정리가 끝나 강사 메모 대기로 넘어간다.
     *
     * @return 이번 호출이 전이시켰으면 true, 이미 메모 대기 이후면 false
     */
    public boolean markNotePending(Instant at) {
        if (status == SessionStatus.NOTE_PENDING || status == SessionStatus.ENDED) {
            return false;
        }
        if (status != SessionStatus.ENDING) {
            throw new IllegalSessionTransitionException();
        }
        this.status = SessionStatus.NOTE_PENDING;
        this.noteDueAt = at.plus(NOTE_WINDOW);
        this.analysisStatus = SessionAnalysisStatus.WAITING_FOR_NOTE;
        return true;
    }

    /**
     * 메모 마감이 지나 최종 종료로 넘긴다. 마감 전 호출은 아무 일도 하지 않는다.
     *
     * <p>{@code analysisStatus}는 건드리지 않는다. 분석 파이프라인의 진행 상태는 그 파이프라인이 소유하며, 여기서 미리 {@code PROCESSING}으로 올리면 아무도 처리하지 않는
     * 세션이 처리 중으로 보인다.
     *
     * @return 이번 호출이 마감시켰으면 true
     */
    public boolean closeNoteWindow(Instant at) {
        if (status != SessionStatus.NOTE_PENDING || noteDueAt == null || at.isBefore(noteDueAt)) {
            return false;
        }
        this.status = SessionStatus.ENDED;
        return true;
    }

    /** 초대 코드로 새 학생을 받을 수 있는가. 시작 전·종료 절차 중에는 받지 않는다(가이드 §6). */
    public boolean acceptsNewParticipants() {
        return status == SessionStatus.LIVE;
    }

    /** 이 역할에게 지금 미디어 토큰을 내줘도 되는가. 강사는 준비 단계부터, 학생은 시작 이후에만 받는다(가이드 §4). */
    public boolean canIssueTokenFor(SessionParticipantRole role) {
        return role == SessionParticipantRole.INSTRUCTOR
                ? status == SessionStatus.PREPARING || status == SessionStatus.LIVE
                : status == SessionStatus.LIVE;
    }

    /** 종료 절차에 들어갔는가. 신규 입장·토큰 발급·화면 공유 승인을 막는 기준이다. */
    public boolean hasStartedEnding() {
        return status == SessionStatus.ENDING || status == SessionStatus.NOTE_PENDING || isEnded();
    }

    public boolean isEnded() {
        return status == SessionStatus.ENDED;
    }

    /** 자동 종료 예정 시각. 아직 시작하지 않았으면 비어 있다. */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(startedAt).map(started -> started.plus(ACTIVE_DURATION));
    }

    public Long id() {
        return id;
    }

    public Long instructorId() {
        return instructorId;
    }

    public String title() {
        return title;
    }

    public String inviteCode() {
        return inviteCode;
    }

    public boolean limitedMode() {
        return limitedMode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public SessionStatus status() {
        return status;
    }

    public SessionAnalysisStatus analysisStatus() {
        return analysisStatus;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public Instant noteDueAt() {
        return noteDueAt;
    }

    public SessionEndReason endReason() {
        return endReason;
    }
}
