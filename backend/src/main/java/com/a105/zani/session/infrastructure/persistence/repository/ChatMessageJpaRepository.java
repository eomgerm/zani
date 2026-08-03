package com.a105.zani.session.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.infrastructure.persistence.entity.ChatMessageJpaEntity;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, Long> {

    /**
     * 채널을 조건에 넣어 비공개 메시지가 공개 이력에 섞이지 않게 한다. 1:1 은 구현하지 않아 현재 PRIVATE 행이 생기지 않지만, 조건 없이 읽으면 나중에 누군가 그 컬럼을 쓰기 시작할 때 조용히
     * 새어 나온다.
     *
     * <p>정렬 방향과 개수는 {@link Pageable} 로 받는다(최근 N 건을 뒤에서 자른다).
     */
    List<ChatMessageJpaEntity> findBySessionIdAndChannelType(Long sessionId, String channelType, Pageable pageable);
}
