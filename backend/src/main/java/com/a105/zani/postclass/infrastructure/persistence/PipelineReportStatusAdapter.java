package com.a105.zani.postclass.infrastructure.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.persistence.repository.PipelineJobJpaRepository;
import com.a105.zani.session.application.get.SessionReportStatus;
import com.a105.zani.session.application.port.SessionReportStatusPort;

/**
 * 사후 처리 단계를 session 이 쓰는 리포트 상태로 옮긴다.
 *
 * <p><b>포트는 session 이 정의하고 구현은 여기 있다.</b> 단계값을 아는 쪽이 postclass 이므로 옮기는 일도 여기서 한다. session 이 postclass 를 참조하면 두 모듈이 서로를
 * 물게 되는데(postclass 는 이미 session 을 참조한다), 이렇게 두면 의존이 {@code postclass → session} 한 방향으로 남는다.
 *
 * <p>덕분에 단계가 늘거나 이름이 바뀌어도 고칠 곳은 이 파일 하나다.
 */
@Component
public class PipelineReportStatusAdapter implements SessionReportStatusPort {

    private final PipelineJobJpaRepository pipelineJobJpaRepository;

    public PipelineReportStatusAdapter(PipelineJobJpaRepository pipelineJobJpaRepository) {
        this.pipelineJobJpaRepository = pipelineJobJpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, SessionReportStatus> findBySessionIds(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            // 빈 IN 절은 DB 마다 다루는 방식이 달라 아예 묻지 않는다.
            return Map.of();
        }

        Map<Long, SessionReportStatus> statuses = new HashMap<>();
        for (Object[] row : pipelineJobJpaRepository.findStatusesBySessionIds(sessionIds)) {
            statuses.put((Long) row[0], toReportStatus((String) row[1]));
        }
        return statuses;
    }

    /**
     * 파이프라인 단계를 화면이 구분해야 하는 셋으로 접는다.
     *
     * <p>모르는 값은 {@code PROCESSING} 으로 둔다. 단계가 새로 생겼는데 여기에 반영되지 않은 상황인데, 그때 {@code COMPLETED} 로 보이면 아직 없는 리포트를 열려다 실패하고
     * {@code FAILED} 로 보이면 멀쩡한 처리를 실패로 알린다. "아직 기다리는 중"이 가장 덜 틀린다.
     */
    private SessionReportStatus toReportStatus(String pipelineStatus) {
        if (PipelineStatus.PUBLISHED.name().equals(pipelineStatus)) {
            return SessionReportStatus.COMPLETED;
        }
        if (PipelineStatus.FAILED.name().equals(pipelineStatus)) {
            return SessionReportStatus.FAILED;
        }
        return SessionReportStatus.PROCESSING;
    }
}
