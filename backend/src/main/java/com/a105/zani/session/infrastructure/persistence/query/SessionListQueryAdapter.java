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

import org.springframework.stereotype.Component;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.member.application.get.GetMemberDisplayNamesQuery;
import com.a105.zani.session.application.get.GetSessionListQueryPort;
import com.a105.zani.session.application.get.SessionReportStatus;
import com.a105.zani.session.application.get.SessionSummaryResult;
import com.a105.zani.session.application.port.SessionReportStatusPort;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

/**
 * 내 수업 목록 읽기 전용 projection.
 *
 * <p><b>세션 수만큼 쿼리가 늘지 않게 한다.</b> 참가자 수·강사 이름·리포트 상태를 세션마다 따로 물으면 목록 길이에 비례해 쿼리가 나간다. 세션 ID 를 모아 한 번씩만 묻고 맵으로 합친다.
 *
 * <p><b>남의 도메인 테이블은 직접 읽지 않는다.</b> 강사 이름은 member 가 공개한 UseCase 로, 리포트 상태는 session 이 정의하고 postclass 가 구현한 포트로 받는다. 여기서
 * 리포지토리나 테이블을 직접 열면 두 모듈의 스키마가 이 파일에 새어 들어와, 그쪽이 바뀔 때 조용히 깨진다.
 */
@Component
public class SessionListQueryAdapter implements GetSessionListQueryPort {

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionParticipantJpaRepository sessionParticipantJpaRepository;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final SessionReportStatusPort sessionReportStatusPort;

    public SessionListQueryAdapter(
            SessionJpaRepository sessionJpaRepository,
            SessionParticipantJpaRepository sessionParticipantJpaRepository,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            SessionReportStatusPort sessionReportStatusPort) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.sessionParticipantJpaRepository = sessionParticipantJpaRepository;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.sessionReportStatusPort = sessionReportStatusPort;
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
        Map<Long, SessionReportStatus> reportStatuses = sessionReportStatusPort.findBySessionIds(mySessionIds);
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
     * <p>{@code SessionJpaEntity.hostMember} 는 지연 로딩이라 세션마다 꺼내 쓰면 목록 길이만큼 쿼리가 나간다. 그렇다고 member 리포지토리를 직접 열면 그쪽이 UseCase
     * 로 이름을 공개하는 이유가 사라지므로, 묶어서 묻는 길을 member 안에 두고 그것을 쓴다.
     */
    private Map<Long, String> findInstructorNames(List<SessionJpaEntity> sessions) {
        Set<Long> hostMemberIds =
                sessions.stream().map(SessionJpaEntity::getHostMemberId).collect(Collectors.toSet());
        return getMemberDisplayNameUseCase.getDisplayNames(new GetMemberDisplayNamesQuery(hostMemberIds));
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
}
