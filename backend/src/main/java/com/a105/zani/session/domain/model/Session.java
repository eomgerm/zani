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
    private SessionStatus status;

    private Session(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            SessionStatus status) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.inviteCode = inviteCode;
        this.limitedMode = limitedMode;
        this.startedAt = startedAt;
        this.status = status;
    }

    public static Session start(Long id, Long instructorId, String title, String inviteCode, Instant startedAt) {
        String trimmedTitle = title == null ? null : title.trim();
        if (trimmedTitle == null || trimmedTitle.isEmpty() || trimmedTitle.length() > TITLE_MAX_LENGTH) {
            throw new InvalidSessionTitleException();
        }
        return new Session(id, instructorId, trimmedTitle, inviteCode, false, startedAt, SessionStatus.LIVE);
    }

    public static Session reconstitute(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            boolean limitedMode,
            Instant startedAt,
            SessionStatus status) {
        return new Session(id, instructorId, title, inviteCode, limitedMode, startedAt, status);
    }

    public Instant expiresAt() {
        return startedAt.plus(ACTIVE_DURATION);
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
}
