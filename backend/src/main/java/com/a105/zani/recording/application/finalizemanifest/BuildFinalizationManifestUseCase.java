package com.a105.zani.recording.application.finalizemanifest;

import com.a105.zani.recording.domain.model.RecordingManifest;

public interface BuildFinalizationManifestUseCase {

    RecordingManifest build(Long sessionId);
}
