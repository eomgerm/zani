package com.a105.zani.session.domain.model;

import java.time.Duration;
import java.time.Instant;

import com.a105.zani.session.domain.exception.InvalidSessionTitleException;

public class Session {

    public static final Duration ACTIVE_DURATION = Duration.ofHours(3);
    private static final int TITLE_MAX_LENGTH = 100;

    private final Long id;
    private final Long instructorId;
    private final String title;
    private final String inviteCode;
    private final boolean limitedMode;
    private final Instant startedAt;

    /** 종료 시각. 수업이 끝날 때 정해지므로 시작 시각과 달리 가변이다. 진행 중이면 비어 있다. */
    private Instant endedAt;

    private SessionStatus status;
    private SessionAnalysisStatus analysisStatus;

    private Session(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            Instant endedAt,
            SessionStatus status,
            SessionAnalysisStatus analysisStatus) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.inviteCode = inviteCode;
        this.limitedMode = limitedMode;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.status = status;
        this.analysisStatus = analysisStatus;
    }

    public static Session start(Long id, Long instructorId, String title, String inviteCode, Instant startedAt) {
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
                startedAt,
                null,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
    }

    public static Session reconstitute(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            Instant endedAt,
            SessionStatus status,
            SessionAnalysisStatus analysisStatus) {
        return new Session(
                id, instructorId, title, inviteCode, limitedMode, startedAt, endedAt, status, analysisStatus);
    }

    public Instant expiresAt() {
        return startedAt.plus(ACTIVE_DURATION);
    }

    /**
     * 세션을 종료 상태로 전환하고 종료 시각을 남긴다.
     *
     * <p>시각을 인자로 받는 이유는 도메인이 시계를 읽지 않기 위해서다. 시계는 서비스가 {@code Clock} 으로 주입받는 것이 이 저장소의 관례이며, 그래야 종료 시점을 테스트에서 고정할 수 있다.
     *
     * <p>이미 끝난 세션에는 아무것도 하지 않는다. 종료는 한 번만 일어난 사건이라, 재시도나 중복 호출이 기록을 뒤로 밀면 그 세션의 리포트 길이가 함께 늘어난다.
     */
    public void end(Instant endedAt) {
        if (isEnded()) {
            return;
        }
        this.status = SessionStatus.ENDED;
        this.endedAt = endedAt;
    }

    public boolean isEnded() {
        return status == SessionStatus.ENDED;
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

    /** 종료 시각. 진행 중이거나, 이 필드를 저장하기 전에 끝난 세션이면 비어 있다. */
    public Instant endedAt() {
        return endedAt;
    }

    public SessionStatus status() {
        return status;
    }

    public SessionAnalysisStatus analysisStatus() {
        return analysisStatus;
    }
}
