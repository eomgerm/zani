package com.a105.zani.session.application.sendchatmessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.port.ChatIdempotencyPort;
import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.port.SessionEventSender;
import com.a105.zani.session.application.port.SessionEventType;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.domain.model.SessionParticipantIdentity;
import com.a105.zani.session.domain.repository.ChatMessageRepository;

/**
 * 공개 채팅 메시지를 저장하고 세션 구독자에게 브로드캐스트한다.
 *
 * <p><b>저장 먼저, 브로드캐스트 나중.</b> 저장에 실패한 메시지를 먼저 뿌리면 화면에는 있고 리포트에는 없는 메시지가 생긴다. 그리고 저장이 만들어 주는 TSID 를 그대로 {@code eventId}
 * 로 쓰기 때문에 순서가 이 방향으로 고정된다.
 *
 * <p><b>메서드에 트랜잭션을 걸지 않는다.</b> 브로드캐스트까지 한 트랜잭션에 넣으면 커밋 전에 메시지가 나가거나, 롤백된 메시지가 이미 전송된 상태가 된다. 저장은 리포지터리 트랜잭션에서 끝내고, 커밋된
 * 뒤에 내보낸다.
 */
@Slf4j
@Service
public class SendChatMessageService implements SendChatMessageUseCase {

    /** 표시 이름을 못 찾은 경우. {@code IssueMediaTokenService} 와 같은 값을 써서 같은 사람이 화면마다 다르게 보이지 않게 한다. */
    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    /** 거절 사유 코드. 화면에 그대로 띄우지 않고 클라이언트가 문구를 고르는 데 쓴다. */
    private static final String REASON_MISSING_CLIENT_EVENT_ID = "MISSING_CLIENT_EVENT_ID";

    private static final String REASON_EMPTY_CONTENT = "EMPTY_CONTENT";
    private static final String REASON_CONTENT_TOO_LONG = "CONTENT_TOO_LONG";
    private static final String REASON_SEND_FAILED = "SEND_FAILED";

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatIdempotencyPort chatIdempotencyPort;
    private final SessionEventPublishPort sessionEventPublishPort;
    private final Clock clock;

    public SendChatMessageService(
            ResolveSessionParticipantUseCase resolveSessionParticipantUseCase,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            ChatMessageRepository chatMessageRepository,
            ChatIdempotencyPort chatIdempotencyPort,
            SessionEventPublishPort sessionEventPublishPort,
            Clock clock) {
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.chatMessageRepository = chatMessageRepository;
        this.chatIdempotencyPort = chatIdempotencyPort;
        this.sessionEventPublishPort = sessionEventPublishPort;
        this.clock = clock;
    }

    @Override
    public SendChatMessageResult send(SendChatMessageCommand command) {
        if (isBlank(command.clientEventId())) {
            // 어느 전송이 실패했는지 짚어 줄 키가 없다. 통지해도 클라이언트가 쓸 수 없어 로그만 남기고 버린다.
            log.warn("clientEventId 없는 채팅 전송을 버립니다. sessionId={}", command.sessionId());
            return SendChatMessageResult.rejected(REASON_MISSING_CLIENT_EVENT_ID);
        }

        String content = command.content() == null ? "" : command.content().trim();
        if (content.isEmpty()) {
            return reject(command, REASON_EMPTY_CONTENT);
        }
        if (content.length() > ChatMessage.MAX_CONTENT_LENGTH) {
            return reject(command, REASON_CONTENT_TOO_LONG);
        }

        // 비멤버·없는 세션·종료된 세션은 여기서 걸러진다. 구독 검사(StompAuthChannelInterceptor)와 같은 판정을 쓴다.
        //
        // 예외로 올리지 않고 거절로 바꾸는 이유: STOMP 는 돌려줄 상태 코드가 없어, 보낸 사람에게 알리지 않으면
        // 클라이언트가 보내는 중 상태로 남는다. 이미 정의된 오류 코드를 사유로 그대로 쓴다.
        ResolveSessionParticipantResult participant;
        try {
            participant = resolveSessionParticipantUseCase.resolve(
                    new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));
        } catch (BusinessException notAllowed) {
            return reject(command, notAllowed.errorCode().code());
        }

        Instant now = clock.instant();
        String eventId = String.valueOf(TsidGenerator.generate());

        Optional<String> alreadyHandled =
                chatIdempotencyPort.claim(command.sessionId(), command.clientEventId(), eventId);
        if (alreadyHandled.isPresent()) {
            Optional<ChatMessage> stored = parseEventId(alreadyHandled.get()).flatMap(chatMessageRepository::findById);
            if (stored.isPresent()) {
                // 같은 전송의 재시도다. 저장은 건너뛰되 **브로드캐스트는 다시 한다.**
                //
                // 조용히 넘기면 첫 전송의 echo 를 놓친 클라이언트가 재시도해도 확인을 받지 못해
                // 보내는 중·실패 상태로 영원히 남는다. 게다가 재연결 스냅샷은 서버가 부여한 eventId 로
                // 이력을 채우므로, 클라이언트가 clientEventId 로 들고 있던 실패 항목과 겹쳐 같은 메시지가
                // 두 번 보인다. 받는 쪽은 eventId 로 거르므로 다시 뿌려도 중복이 생기지 않는다.
                publish(command, participant, stored.get(), alreadyHandled.get(), now);
                return SendChatMessageResult.duplicate(alreadyHandled.get());
            }
            // 선점만 남고 행이 없다 — 앞선 저장이 실패했고 되돌리기까지 실패한 경우다.
            //
            // 지우기만 하고 이어서 저장하면 이 전송이 멱등 보호를 받지 못한다. 뒤따라온 재시도가 처음 보는 값으로
            // 판단돼 한 번 더 저장된다. 새 식별자로 덮어써 선점과 행을 다시 맞춘다.
            log.warn("멱등 선점만 남고 채팅 행이 없어 새 전송으로 처리합니다. sessionId={}", command.sessionId());
            chatIdempotencyPort.reclaim(command.sessionId(), command.clientEventId(), eventId);
        }

        ChatMessage saved;
        try {
            saved = chatMessageRepository.save(ChatMessage.post(
                    Long.parseLong(eventId),
                    command.sessionId(),
                    participant.participantId(),
                    content,
                    offsetMs(participant.sessionStartedAt(), now)));
        } catch (RuntimeException failedToSave) {
            // 선점을 되돌리지 않으면 같은 clientEventId 로 다시 보낸 재시도가 중복으로 걸러져 메시지가 영구히 사라진다.
            chatIdempotencyPort.release(command.sessionId(), command.clientEventId());
            log.error("채팅 저장에 실패했습니다. sessionId={}", command.sessionId(), failedToSave);
            return reject(command, REASON_SEND_FAILED);
        }

        publish(command, participant, saved, eventId, now);
        return SendChatMessageResult.sent(eventId);
    }

    /** 보낸 사람에게만 알린다. 다른 참가자에게 뿌릴 이유가 없다. */
    private SendChatMessageResult reject(SendChatMessageCommand command, String reason) {
        sessionEventPublishPort.publishRejection(
                String.valueOf(command.userId()), new SessionEventRejection(command.clientEventId(), reason));
        return SendChatMessageResult.rejected(reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void publish(
            SendChatMessageCommand command,
            ResolveSessionParticipantResult participant,
            ChatMessage message,
            String eventId,
            Instant deliveredAt) {
        sessionEventPublishPort.publishToSession(
                command.sessionId(),
                new SessionEvent(
                        eventId,
                        command.clientEventId(),
                        SessionEventType.CHAT_MESSAGE,
                        sender(participant, command.userId()),
                        message.occurredOffsetMs(),
                        deliveredAt,
                        Map.of("content", message.content())));
    }

    /** 우리가 쓴 값이라 항상 숫자지만, 읽을 수 없으면 재시도로 보지 않고 새 전송으로 넘긴다. */
    private static Optional<Long> parseEventId(String eventId) {
        try {
            return Optional.of(Long.parseLong(eventId));
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    private SessionEventSender sender(ResolveSessionParticipantResult participant, Long userId) {
        // 표시 이름은 member 도메인의 읽기 UseCase 로만 조회한다(크로스도메인은 공개 API 경유).
        String displayName = getMemberDisplayNameUseCase
                .getDisplayName(new GetMemberDisplayNameQuery(userId))
                .orElse(DEFAULT_DISPLAY_NAME);
        return new SessionEventSender(
                SessionParticipantIdentity.of(participant.participantId()), displayName, participant.role());
    }

    /**
     * 수업 시작으로부터 흐른 밀리초.
     *
     * <p>0 으로 바닥을 둔다. 서버 시계가 뒤로 조정되면 음수가 나올 수 있는데, 그때 예외를 던지면 시계 보정 한 번에 채팅이 멎는다. 순서는 TSID 가 지키므로 이 값이 조금 눌리는 편이 낫다.
     */
    private long offsetMs(Instant sessionStartedAt, Instant at) {
        return Math.max(0L, Duration.between(sessionStartedAt, at).toMillis());
    }
}
