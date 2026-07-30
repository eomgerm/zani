package com.a105.zani.postclass.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;
import com.a105.zani.postclass.infrastructure.persistence.entity.InstructorNoteJpaEntity;

/** instructor_notes 매핑. 본문은 V1 스키마의 content("세션 전체 메모, 최대 5000자이며 빈 값 허용")에 그대로 담는다. */
@Component
public class InstructorNotePersistenceMapper {

    public InstructorNoteJpaEntity toEntity(InstructorNote note) {
        return InstructorNoteJpaEntity.builder()
                .id(note.id() != null ? note.id() : TsidGenerator.generate())
                .sessionId(note.sessionId())
                .instructorParticipantId(note.instructorParticipantId())
                .content(note.content())
                .status(note.status().name())
                .lastEditedAt(note.lastEditedAt())
                .finalizedAt(note.finalizedAt())
                .build();
    }

    public InstructorNote toDomain(InstructorNoteJpaEntity entity) {
        return InstructorNote.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getInstructorParticipantId(),
                NoteStatus.valueOf(entity.getStatus()),
                entity.getContent(),
                entity.getLastEditedAt(),
                entity.getFinalizedAt());
    }
}
