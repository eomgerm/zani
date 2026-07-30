package com.a105.zani.attention.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.getattentiontimeline.AttentionTimelineQueryPort;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.PromptRecord;
import com.a105.zani.attention.infrastructure.persistence.repository.AttentionTimelineJpaRepository;

/**
 * 저장된 이력을 도메인 레코드로 옮긴다.
 *
 * <p>서버가 모르는 열거 값은 버리고 로그만 남긴다. 검출기 계약이 넓어지면 배포 순서에 따라 서버보다 앞선 값이 먼저 저장될 수 있는데, 그때 리포트 전체가 500 으로 죽는 것보다 그 관측 하나가 빠지는
 * 편이 낫다. 사후 조회라 되돌릴 기회도 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttentionTimelineQueryAdapter implements AttentionTimelineQueryPort {

    private final AttentionTimelineJpaRepository repository;

    @Override
    public List<ObservationRecord> observations(long sessionId) {
        return toObservations(repository.findObservations(sessionId));
    }

    @Override
    public List<ObservationRecord> observations(long sessionId, long participantId) {
        return toObservations(repository.findObservations(sessionId, participantId));
    }

    @Override
    public List<PromptRecord> prompts(long sessionId) {
        return toPrompts(repository.findPrompts(sessionId));
    }

    @Override
    public List<PromptRecord> prompts(long sessionId, long participantId) {
        return toPrompts(repository.findPrompts(sessionId, participantId));
    }

    private List<ObservationRecord> toObservations(List<ObservationRow> rows) {
        List<ObservationRecord> records = new ArrayList<>(rows.size());
        for (ObservationRow row : rows) {
            DetectorOutcome outcome;
            try {
                outcome = DetectorOutcome.valueOf(row.detectorOutcome());
            } catch (IllegalArgumentException exception) {
                log.warn("Skipping an unknown detector outcome: {}", row.detectorOutcome());
                continue;
            }
            records.add(new ObservationRecord(row.participantId(), row.offsetMs(), outcome));
        }
        return List.copyOf(records);
    }

    private List<PromptRecord> toPrompts(List<PromptRow> rows) {
        List<PromptRecord> records = new ArrayList<>(rows.size());
        for (PromptRow row : rows) {
            PromptAnswer answer;
            try {
                answer = PromptAnswer.valueOf(row.response());
            } catch (IllegalArgumentException exception) {
                log.warn("Skipping an unknown prompt response: {}", row.response());
                continue;
            }
            records.add(new PromptRecord(row.participantId(), row.offsetMs(), answer));
        }
        return List.copyOf(records);
    }
}
