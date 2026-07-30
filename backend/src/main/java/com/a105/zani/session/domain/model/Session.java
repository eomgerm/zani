package com.a105.zani.session.domain.model;

import java.time.Duration;
import java.time.Instant;

import com.a105.zani.session.domain.exception.InvalidSessionTitleException;
import com.a105.zani.session.domain.exception.SessionNotLiveException;

/**
 * 수업 세션. 생명주기 전이(준비 → 시작 → 정리 → 메모 대기)는 모두 이 안에서만 일어난다.
 *
 * <p>전이는 역행하지 않고 멱등하다. 같은 전이를 두 번 요청하면 두 번째는 아무 것도 바꾸지 않고 {@code false} 를 돌려준다 — 재시도·중복 요청이 시작 시각을 밀거나 종료 시각을 덮어쓰지 못하게
 * 하려는 것이다. 그래서 전이 메서드는 void 가 아니라 "실제로 바뀌었는지"를 돌려준다.
 */
public class Session {

    public static final Duration ACTIVE_DURATION = Duration.ofHours(3);
    /** 한 수업에 동시에 들어올 수 있는 사람 수(강사 포함). */
    public static final int CAPACITY = 30;

    private static final int TITLE_MAX_LENGTH = 100;

    private final Long id;
    private final Long instructorId;
    private final String title;
    private final String inviteCode;
    private final boolean limitedMode;
    private SessionStatus status;
    private SessionAnalysisStatus analysisStatus;
    private Instant startedAt;
    private Instant endedAt;
    private SessionEndReason endReason;

    private Session(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            SessionStatus status,
            SessionAnalysisStatus analysisStatus,
            Instant startedAt,
            Instant endedAt,
            SessionEndReason endReason) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.inviteCode = inviteCode;
        this.limitedMode = limitedMode;
        this.status = status;
        this.analysisStatus = analysisStatus;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.endReason = endReason;
    }

    /**
     * 수업을 준비 상태로 만든다. 아직 시작하지 않았으므로 시작 시각이 없고, 초대 코드로 학생이 들어올 수 없다.
     *
     * <p>생성과 시작을 나누는 이유는 강사가 카메라·마이크를 맞추는 동안 학생이 빈 방에 들어오지 않게 하려는 것이다.
     */
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
            SessionStatus status,
            SessionAnalysisStatus analysisStatus,
            Instant startedAt,
            Instant endedAt,
            SessionEndReason endReason) {
        return new Session(
                id,
                instructorId,
                title,
                inviteCode,
                limitedMode,
                status,
                analysisStatus,
                startedAt,
                endedAt,
                endReason);
    }

    /**
     * 수업을 실제로 시작한다. 이 시점부터 초대 코드가 유효해지고 3시간 자동 종료 시계가 돌기 시작한다.
     *
     * <p>시작 시각을 생성 시각이 아니라 이 순간으로 잡는 게 핵심이다. 강사가 준비에 20분을 쓰면 생성 시각 기준으로는 수업 시간이 20분 줄어든다.
     *
     * @return 이번 호출로 시작됐으면 true, 이미 진행 중이었으면 false
     * @throws SessionNotLiveException 이미 종료 절차에 들어간 세션이면
     */
    public boolean start(Instant startedAt) {
        if (status == SessionStatus.LIVE) {
            return false;
        }
        if (status.isClosed()) {
            throw new SessionNotLiveException();
        }
        this.status = SessionStatus.LIVE;
        this.startedAt = startedAt;
        return true;
    }

    /**
     * 종료 절차를 시작한다. 새 입장과 미디어 발급이 이 시점부터 닫힌다.
     *
     * @return 이번 호출로 전이됐으면 true, 이미 종료 절차에 들어가 있었으면 false
     */
    public boolean beginEnding(Instant endedAt, SessionEndReason reason) {
        if (status.isClosed()) {
            return false;
        }
        this.status = SessionStatus.ENDING;
        this.endedAt = endedAt;
        this.endReason = reason;
        return true;
    }

    /**
     * 정리를 마치고 강사 메모를 기다리는 상태로 넘어간다.
     *
     * @return 이번 호출로 전이됐으면 true, 이미 그 이후 상태였으면 false
     */
    public boolean awaitNote() {
        if (status != SessionStatus.ENDING) {
            return false;
        }
        this.status = SessionStatus.NOTE_PENDING;
        return true;
    }

    /** 시작 시각 + 3시간. 아직 시작하지 않은 세션은 자동 종료 시계가 돌지 않으므로 null 이다. */
    public Instant expiresAt() {
        return startedAt == null ? null : startedAt.plus(ACTIVE_DURATION);
    }

    /** 수업이 진행 중인가. 학생 입장·접속 집계는 이 상태에서만 열린다. */
    public boolean isLive() {
        return status.isLive();
    }

    /** 종료 요청 이후인가. 참가자를 더 받지 않고 미디어도 내주지 않는다. */
    public boolean isClosed() {
        return status.isClosed();
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

    public Instant endedAt() {
        return endedAt;
    }

    public SessionEndReason endReason() {
        return endReason;
    }

    public SessionStatus status() {
        return status;
    }

    public SessionAnalysisStatus analysisStatus() {
        return analysisStatus;
    }
}
