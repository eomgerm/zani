package com.a105.zani.recording.application.finalizerecording;

import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;

public interface FinalizeRecordingUseCase {

    void finalizeRecording(FinalizationJobLease lease);
}
