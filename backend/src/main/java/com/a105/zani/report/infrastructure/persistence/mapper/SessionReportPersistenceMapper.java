package com.a105.zani.report.infrastructure.persistence.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;

@Component
public class SessionReportPersistenceMapper {

    /** 공개 시각은 넣지 않는다. 저장과 공개는 분리돼 있고 공개는 파이프라인의 마지막 단계가 일괄로 한다. */
    public SessionReportJpaEntity toEntity(SessionReport report) {
        return SessionReportJpaEntity.builder()
                .id(TsidGenerator.generate())
                .sessionId(report.sessionId())
                .summary(report.summary())
                .build();
    }

    /** 구간은 애그리거트가 이미 시간순으로 검증한 순서를 그대로 쓴다. */
    public List<SessionSectionJpaEntity> toSectionEntities(SessionReport report) {
        return report.sections().stream()
                .map(section -> SessionSectionJpaEntity.builder()
                        .id(TsidGenerator.generate())
                        .sessionId(report.sessionId())
                        .title(section.title())
                        .summary(section.summary())
                        .startedOffsetMs(section.startOffsetMs())
                        .endedOffsetMs(section.endOffsetMs())
                        .build())
                .toList();
    }
}
