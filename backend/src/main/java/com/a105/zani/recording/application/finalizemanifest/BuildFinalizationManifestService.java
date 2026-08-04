package com.a105.zani.recording.application.finalizemanifest;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.gettrackfiles.SessionTrackFile;
import com.a105.zani.recording.application.gettrackfiles.SessionTrackFileQueryPort;
import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;
import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.domain.model.RecordingTrackEntry;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextQuery;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextResult;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextUseCase;
import com.a105.zani.session.application.getrecordingcontext.SessionRecordingParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** DB 메타데이터만 사용해 worker 입력 manifest를 결정적으로 조립한다. 파일명으로 화자나 source를 추정하지 않는다. */
@Service
@RequiredArgsConstructor
public class BuildFinalizationManifestService implements BuildFinalizationManifestUseCase {

    private static final Comparator<TrackWithId> TRACK_ORDER = Comparator.comparingLong(
                    (TrackWithId value) -> value.entry().offsetMs())
            .thenComparing(value -> value.entry().participantIdentity())
            .thenComparing(value -> value.entry().source().name())
            .thenComparing(value -> value.entry().relativePath())
            .thenComparingLong(TrackWithId::recordingFileId);

    private final SessionTrackFileQueryPort sessionTrackFileQueryPort;
    private final GetSessionRecordingContextUseCase getSessionRecordingContextUseCase;

    @Override
    @Transactional(readOnly = true)
    public RecordingManifest build(Long sessionId) {
        GetSessionRecordingContextResult context =
                getSessionRecordingContextUseCase.get(new GetSessionRecordingContextQuery(sessionId));
        Map<Long, ParticipantManifestIdentity> identities = identities(context.participants());

        List<TrackWithId> tracks = sessionTrackFileQueryPort.findBySessionId(sessionId).stream()
                .map(file -> toTrack(file, identities))
                .sorted(TRACK_ORDER)
                .toList();

        return RecordingManifest.create(
                String.valueOf(sessionId),
                context.startedAt(),
                tracks.stream().map(TrackWithId::entry).toList());
    }

    private static Map<Long, ParticipantManifestIdentity> identities(List<SessionRecordingParticipant> participants) {
        Map<Long, ParticipantManifestIdentity> result = new HashMap<>();
        List<SessionRecordingParticipant> students = participants.stream()
                .filter(participant -> participant.role() == SessionParticipantRole.STUDENT)
                .sorted(Comparator.comparingLong(SessionRecordingParticipant::id))
                .toList();

        for (SessionRecordingParticipant participant : participants) {
            if (participant.id() == null || participant.role() == null) {
                throw new InvalidRecordingManifestException();
            }
            if (participant.role() == SessionParticipantRole.INSTRUCTOR) {
                result.put(
                        participant.id(),
                        new ParticipantManifestIdentity(
                                RecordingAlias.instructor().value(), participant.role()));
            }
        }
        for (int index = 0; index < students.size(); index++) {
            SessionRecordingParticipant student = students.get(index);
            result.put(
                    student.id(),
                    new ParticipantManifestIdentity(
                            RecordingAlias.student(index + 1).value(), student.role()));
        }
        return Map.copyOf(result);
    }

    private static TrackWithId toTrack(SessionTrackFile file, Map<Long, ParticipantManifestIdentity> identities) {
        if (file.recordingFileId() == null
                || file.sessionParticipantId() == null
                || file.trackSource() == null
                || file.startedOffsetMs() == null
                || file.endedOffsetMs() == null) {
            throw new InvalidRecordingManifestException();
        }
        ParticipantManifestIdentity identity = identities.get(file.sessionParticipantId());
        if (identity == null) {
            throw new InvalidRecordingManifestException();
        }
        long duration;
        try {
            duration = Math.subtractExact(file.endedOffsetMs(), file.startedOffsetMs());
        } catch (ArithmeticException overflow) {
            throw new InvalidRecordingManifestException();
        }
        RecordingTrackEntry entry = new RecordingTrackEntry(
                identity.alias(),
                identity.role(),
                file.trackSource(),
                file.storageKey(),
                file.startedOffsetMs(),
                duration,
                null);
        return new TrackWithId(file.recordingFileId(), entry);
    }

    private record ParticipantManifestIdentity(String alias, SessionParticipantRole role) {}

    private record TrackWithId(Long recordingFileId, RecordingTrackEntry entry) {}
}
