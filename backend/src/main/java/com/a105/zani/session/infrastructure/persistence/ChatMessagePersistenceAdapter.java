package com.a105.zani.session.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.domain.repository.ChatMessageRepository;
import com.a105.zani.session.infrastructure.persistence.entity.ChatMessageJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.ChatMessagePersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.ChatMessageJpaRepository;

@Component
public class ChatMessagePersistenceAdapter implements ChatMessageRepository {

    /** 같은 밀리초에 들어온 두 메시지의 순서를 고정하는 2차 정렬 키. TSID 는 시간 순서를 담고 있어 발급 순서와 같다. 없으면 정렬이 불안정해 스냅샷과 실시간 스트림의 순서가 어긋난다. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredOffsetMs"), Sort.Order.desc("id"));

    private final ChatMessageJpaRepository chatMessageJpaRepository;
    private final ChatMessagePersistenceMapper mapper;

    public ChatMessagePersistenceAdapter(
            ChatMessageJpaRepository chatMessageJpaRepository, ChatMessagePersistenceMapper mapper) {
        this.chatMessageJpaRepository = chatMessageJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public ChatMessage save(ChatMessage chatMessage) {
        ChatMessageJpaEntity saved = chatMessageJpaRepository.saveAndFlush(mapper.toEntity(chatMessage));
        return mapper.toDomain(saved);
    }

    @Override
    public List<ChatMessage> findRecentPublic(Long sessionId, int limit) {
        // 최근 N 건은 최신순으로 잘라야 얻을 수 있고, 화면은 오래된 것부터 위에 쌓으므로 되돌려 준다.
        List<ChatMessageJpaEntity> newestFirst = chatMessageJpaRepository.findBySessionIdAndChannelType(
                sessionId, ChatMessagePersistenceMapper.PUBLIC_CHANNEL, PageRequest.of(0, limit, NEWEST_FIRST));

        List<ChatMessage> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            oldestFirst.add(mapper.toDomain(newestFirst.get(i)));
        }
        return List.copyOf(oldestFirst);
    }
}
