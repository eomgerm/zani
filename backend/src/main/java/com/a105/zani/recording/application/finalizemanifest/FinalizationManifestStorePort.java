package com.a105.zani.recording.application.finalizemanifest;

import com.a105.zani.recording.domain.model.RecordingManifest;

/** 확정된 manifest를 최종 산출물 루트에 원자적으로 저장한다. */
public interface FinalizationManifestStorePort {

    void store(Long sessionId, RecordingManifest manifest);
}
