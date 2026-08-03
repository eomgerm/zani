package com.a105.zani.session.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.member.infrastructure.persistence.repository.MemberJpaRepository;
import com.a105.zani.session.application.get.GetSessionListQueryPort;
import com.a105.zani.session.application.get.SessionReportStatus;
import com.a105.zani.session.application.get.SessionSummaryResult;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

/**
 * 내 수업 목록 읽기 전용 projection.
 *
 * <p><b>세션 수만큼 쿼리가 늘지 않게 한다.</b> 참가자 수와 리포트 상태는 세션마다 따로 물으면 목록 길이에 비례해 쿼리가 나간다. 세션 ID 를 모아 한 번씩만 묻고 맵으로 합친다.
 *
 * <p><b>{@code pipeline_jobs} 는 postclass 소유 테이블이다.</b> 그 모듈의 엔티티를 가져다 쓰면 postclass 가 이미 session 을 참조하고 있으므로 모듈 사이에 순환이
 * 생긴다. 읽기 전용 projection 한 곳에서만 필요한 값이라, 자바 의존 없이 네이티브 쿼리로 상태 문자열만 가져온다.
 */
@Component
public class SessionListQueryAdapter implements GetSessionListQueryPort {

    /** 세션당 한 행이 보장된다({@code UK_PIPELINE_JOBS_SESSION}). 그래서 그룹핑 없이 그대로 맵으로 접을 수 있다. */
    private static final String PIPELINE_STATUS_SQL =
            "SELECT session_id, status FROM pipeline_jobs WHERE session_id IN (%s)";

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionParticipantJpaRepository sessionParticipantJpaRepository;
    private final MemberJpaRepository memberJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    public SessionListQueryAdapter(
            SessionJpaRepository sessionJpaRepository,
            SessionParticipantJpaRepository sessionParticipantJpaRepository,
            MemberJpaRepository memberJpaRepository,
            JdbcTemplate jdbcTemplate) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.sessionParticipantJpaRepository = sessionParticipantJpaRepository;
        this.memberJpaRepository = memberJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SessionSummaryResult> findByUserId(long userId) {
        List<SessionJpaEntity> owned = sessionJpaRepository.findByHostMemberId(userId);
        List<SessionParticipantJpaEntity> myParticipations = sessionParticipantJpaRepository.findByMemberId(userId);

        Map<Long, SessionJpaEntity> joinedSessionsById =
                sessionJpaRepository
                        .findAllById(myParticipations.stream()
                                .map(SessionParticipantJpaEntity::getSessionId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(SessionJpaEntity::getId, Function.identity()));

        // 강사는 자기 수업의 참가자이기도 하므로 주최 목록과 참가 목록에 같은 세션이 함께 잡힌다.
        // 그대로 두면 내 수업 목록에 같은 수업이 두 번 뜬다. 주최 항목을 우선해 걸러낸다.
        Map<Long, SessionParticipantRole> roleBySessionId = new HashMap<>();
        Set<Long> mySessionIds = new LinkedHashSet<>();
        List<SessionJpaEntity> sessions = new ArrayList<>();

        for (SessionJpaEntity session : owned) {
            if (mySessionIds.add(session.getId())) {
                sessions.add(session);
                roleBySessionId.put(session.getId(), SessionParticipantRole.INSTRUCTOR);
            }
        }
        for (SessionParticipantJpaEntity participation : myParticipations) {
            SessionJpaEntity session = joinedSessionsById.get(participation.getSessionId());
            if (session != null && mySessionIds.add(session.getId())) {
                sessions.add(session);
                roleBySessionId.put(session.getId(), participation.getRole());
            }
        }

        Map<Long, Long> participantCounts = countParticipants(mySessionIds);
        Map<Long, SessionReportStatus> reportStatuses = findReportStatuses(mySessionIds);
        Map<Long, String> instructorNames = findInstructorNames(sessions);
        // 재입장은 참가자 행이 있어야 성립한다. 주최자라도 행이 없으면 미디어 토큰이 거절되므로 버튼을 보여 주면 안 된다.
        Set<Long> sessionIdsIHaveJoined = myParticipations.stream()
                .map(SessionParticipantJpaEntity::getSessionId)
                .collect(Collectors.toSet());

        List<SessionSummaryResult> results = new ArrayList<>(sessions.size());
        for (SessionJpaEntity session : sessions) {
            results.add(new SessionSummaryResult(
                    session.getId(),
                    session.getInviteCode(),
                    session.getTitle(),
                    instructorNames.get(session.getHostMemberId()),
                    session.getStatus(),
                    roleBySessionId.get(session.getId()),
                    session.getStartedAt(),
                    session.getEndedAt(),
                    participantCounts.getOrDefault(session.getId(), 0L),
                    reportStatuses.getOrDefault(session.getId(), SessionReportStatus.NONE),
                    session.getStatus() == SessionStatus.LIVE && sessionIdsIHaveJoined.contains(session.getId())));
        }
        return results;
    }

    /**
     * 주최 강사 이름을 한 번에 가져온다.
     *
     * <p>{@code SessionJpaEntity.hostMember} 는 지연 로딩이라 세션마다 꺼내 쓰면 목록 길이만큼 쿼리가 나간다. 학생 카드가 "누구 수업인지"를 보여주는 데만 쓰는 값이라 이름
     * 하나를 위해 그럴 이유가 없다.
     */
    private Map<Long, String> findInstructorNames(List<SessionJpaEntity> sessions) {
        Set<Long> hostMemberIds =
                sessions.stream().map(SessionJpaEntity::getHostMemberId).collect(Collectors.toSet());
        if (hostMemberIds.isEmpty()) {
            return Map.of();
        }
        return memberJpaRepository.findAllById(hostMemberIds).stream()
                .collect(Collectors.toMap(MemberJpaEntity::getId, MemberJpaEntity::getDisplayName));
    }

    private Map<Long, Long> countParticipants(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : sessionParticipantJpaRepository.countGroupedBySessionIds(sessionIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private Map<Long, SessionReportStatus> findReportStatuses(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = sessionIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Map<Long, SessionReportStatus> statuses = new HashMap<>();
        jdbcTemplate.query(
                PIPELINE_STATUS_SQL.formatted(placeholders),
                resultSet -> {
                    statuses.put(
                            resultSet.getLong("session_id"), SessionReportStatus.from(resultSet.getString("status")));
                },
                sessionIds.toArray());
        return statuses;
    }
}
