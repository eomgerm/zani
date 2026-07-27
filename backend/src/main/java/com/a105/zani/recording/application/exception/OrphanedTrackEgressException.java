package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * LiveKit Egress는 이미 시작됐지만 recordings 행 저장이 실패한 상태. 재시도하면 같은 트랙에 두 번째 Egress가 붙으므로 재시도 대상이 아니며, 발급된 egressId를 남겨 대조·회수
 * 작업이 처리하게 한다.
 */
public class OrphanedTrackEgressException extends BusinessException {

    private final String egressId;

    public OrphanedTrackEgressException(String egressId, Throwable cause) {
        super(RecordingApplicationErrorCode.TRACK_EGRESS_ORPHANED, cause);
        this.egressId = egressId;
    }

    public String egressId() {
        return egressId;
    }
}
