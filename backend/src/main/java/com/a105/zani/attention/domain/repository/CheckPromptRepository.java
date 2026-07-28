package com.a105.zani.attention.domain.repository;

import java.util.Optional;

import com.a105.zani.attention.domain.model.CheckPrompt;
import com.a105.zani.attention.domain.model.PromptKind;

public interface CheckPromptRepository {

    /**
     * 같은 프롬프트에 이미 답이 기록돼 있는지 찾는다.
     *
     * <p>브라우저가 프롬프트를 띄우므로 서버에는 표시 시점의 행이 없다. 재시도를 알아보는 기준은 (참가자, 종류, 표시 시각)이다.
     */
    Optional<CheckPrompt> findByParticipantAndKindAndShownOffset(
            Long sessionId, Long participantId, PromptKind kind, long shownOffsetMs);

    CheckPrompt save(CheckPrompt checkPrompt);
}
