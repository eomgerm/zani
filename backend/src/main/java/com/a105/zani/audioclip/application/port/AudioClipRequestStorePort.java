package com.a105.zani.audioclip.application.port;

import java.util.List;
import java.util.Optional;

import com.a105.zani.audioclip.domain.model.AudioClipRequest;

/** 클립 요청 수명주기 기록을 짧은 보존 기간의 저장소(Redis)에 보관하는 포트. 오디오 바이트는 절대 저장하지 않는다. 벤더 타입은 인프라 어댑터 안에만 존재한다. */
public interface AudioClipRequestStorePort {

    /** 요청을 저장하거나 상태 전이를 덮어쓴다. 보존 기간은 어댑터가 관리한다. */
    void save(AudioClipRequest request);

    Optional<AudioClipRequest> find(long clipId);

    /**
     * 아직 처리되지 않은(PENDING) 요청 목록. 만료 여부는 판단하지 않으므로 호출자가 {@link AudioClipRequest#isExpired}로 걸러야 한다. SSE 재구독 리플레이의 입력이다.
     */
    List<AudioClipRequest> findPending(long sessionId);
}
