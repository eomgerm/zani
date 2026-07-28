package com.a105.zani.attention.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.attention.domain.model.CheckPrompt;
import com.a105.zani.attention.domain.model.CheckPromptStatus;
import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.PromptKind;
import com.a105.zani.attention.infrastructure.persistence.entity.CheckPromptJpaEntity;
import com.a105.zani.common.persistence.TsidGenerator;

/** check_prompts 테이블 매핑. 프롬프트 종류는 trigger_type("프롬프트를 발생시킨 규칙 유형")에, 답은 response 에 enum 이름 그대로 담는다. */
@Component
public class CheckPromptPersistenceMapper {

    public CheckPromptJpaEntity toEntity(CheckPrompt checkPrompt) {
        return CheckPromptJpaEntity.builder()
                .id(checkPrompt.id() != null ? checkPrompt.id() : TsidGenerator.generate())
                .sessionId(checkPrompt.sessionId())
                .sessionParticipantId(checkPrompt.participantId())
                .triggerType(checkPrompt.kind().name())
                .status(checkPrompt.status().name())
                .response(checkPrompt.answer().name())
                .shownOffsetMs(checkPrompt.shownOffsetMs())
                .respondedOffsetMs(checkPrompt.respondedOffsetMs())
                .build();
    }

    public CheckPrompt toDomain(CheckPromptJpaEntity entity) {
        return CheckPrompt.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getSessionParticipantId(),
                PromptKind.valueOf(entity.getTriggerType()),
                CheckPromptStatus.valueOf(entity.getStatus()),
                PromptAnswer.valueOf(entity.getResponse()),
                entity.getShownOffsetMs(),
                entity.getRespondedOffsetMs());
    }
}
