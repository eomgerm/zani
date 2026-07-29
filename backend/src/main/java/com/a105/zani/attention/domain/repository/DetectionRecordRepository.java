package com.a105.zani.attention.domain.repository;

import com.a105.zani.attention.domain.model.DetectionRecord;

public interface DetectionRecordRepository {

    /**
     * 판정 한 건을 남긴다.
     *
     * <p>같은 참가자가 같은 clientEventId 로 다시 보내면 DB 유니크 제약이 막는다. 그 경우 {@code false} 를 돌려주고 새 행을 만들지 않는다.
     */
    boolean saveIfNew(DetectionRecord record);
}
