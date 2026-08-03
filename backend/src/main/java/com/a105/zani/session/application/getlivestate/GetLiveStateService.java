package com.a105.zani.session.application.getlivestate;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.port.RaisedHandQueuePort;
import com.a105.zani.session.application.port.SessionEventSender;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantIdentity;
import com.a105.zani.session.domain.repository.ChatMessageRepository;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

/**
 * 재연결 직후 화면을 되돌릴 현재 상태를 모아 준다.
 *
 * <p><b>이력을 STOMP 로 흘리지 않는 이유.</b> 소켓으로 재생하면 "재생이 끝났음"을 알리는 표시가 필요하고, 재생 중 도착한 실시간 메시지와의 중복도 클라이언트가 따로 처리해야 한다. REST 로
 * 한 번 받고 {@code eventId} 로 겹치는 것만 걸러내는 편이 단순하다.
 *
 * <p>클라이언트는 <b>구독을 먼저</b> 걸고 그다음 이 스냅샷을 받아야 한다. 순서를 뒤집으면 두 호출 사이에 도착한 메시지가 어디에도 없다.
 */
@Service
public class GetLiveStateService implements GetLiveStateUseCase {

    /** 스냅샷에 담는 최근 채팅 건수. 세 시간짜리 수업의 채팅을 전부 내리면 재연결이 잦은 구간에서 그 비용이 반복된다. 더 옛 메시지가 필요하면 리포트가 담당한다. */
    private static final int CHAT_HISTORY_LIMIT = 200;

    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final SessionParticipantRepository participantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final RaisedHandQueuePort raisedHandQueuePort;

    public GetLiveStateService(
            ResolveSessionParticipantUseCase resolveSessionParticipantUseCase,
            SessionParticipantRepository participantRepository,
            ChatMessageRepository chatMessageRepository,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            RaisedHandQueuePort raisedHandQueuePort) {
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
        this.participantRepository = participantRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.raisedHandQueuePort = raisedHandQueuePort;
    }

    @Override
    @Transactional(readOnly = true)
    public LiveStateResult get(GetLiveStateQuery query) {
        // 비멤버에게 이력을 보여주지 않는다. 구독 검사(StompAuthChannelInterceptor)와 같은 판정이다.
        resolveSessionParticipantUseCase.resolve(new ResolveSessionParticipantQuery(query.sessionId(), query.userId()));

        // 큐가 준 순서 그대로 내려보낸다. 클라이언트는 포함 여부만 쓰므로 여기서 다시 정렬하지 않는다.
        return new LiveStateResult(
                directoryOf(query.sessionId()),
                chatHistoryOf(query.sessionId()),
                raisedHandQueuePort.raisedInOrder(query.sessionId()));
    }

    /** 참가자 디렉터리. 이름 조회 횟수가 <b>메시지 수가 아니라 참가자 수</b>로 묶인다는 점이 중요하다 — 200 건의 채팅이 있어도 한 수업의 참가자는 수십 명이다. */
    private List<SessionEventSender> directoryOf(Long sessionId) {
        return participantRepository.findBySessionId(sessionId).stream()
                .map(this::toSender)
                .toList();
    }

    private SessionEventSender toSender(SessionParticipant participant) {
        String displayName = getMemberDisplayNameUseCase
                .getDisplayName(new GetMemberDisplayNameQuery(participant.userId()))
                .orElse(DEFAULT_DISPLAY_NAME);
        return new SessionEventSender(SessionParticipantIdentity.of(participant.id()), displayName, participant.role());
    }

    private List<LiveChatMessage> chatHistoryOf(Long sessionId) {
        return chatMessageRepository.findRecentPublic(sessionId, CHAT_HISTORY_LIMIT).stream()
                .map(GetLiveStateService::toLiveChatMessage)
                .toList();
    }

    private static LiveChatMessage toLiveChatMessage(ChatMessage message) {
        return new LiveChatMessage(
                String.valueOf(message.id()),
                SessionParticipantIdentity.of(message.senderParticipantId()),
                message.occurredOffsetMs(),
                message.content());
    }
}
