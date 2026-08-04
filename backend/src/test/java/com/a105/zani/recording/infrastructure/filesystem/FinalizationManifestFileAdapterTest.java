package com.a105.zani.recording.infrastructure.filesystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.domain.model.RecordingTrackEntry;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationProperties;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizationManifestFileAdapterTest {

    private static final Long SESSION_ID = 269L;
    private static final Instant STARTED_AT = Instant.parse("2026-08-04T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void worker_계약의_snake_case_JSON을_원자적_경로에_저장한다() throws Exception {
        FinalizationManifestFileAdapter adapter = adapter();

        adapter.store(SESSION_ID, manifest("raw/instructor/camera.webm"));

        Path target = tempDir.resolve("269/manifest/tracks.json");
        JsonNode json = new ObjectMapper().readTree(target.toFile());
        assertEquals(1, json.get("schema_version").asInt());
        assertEquals("269", json.get("session_id").asText());
        assertEquals("2026-08-04T01:00:00Z", json.get("timeline_started_at").asText());
        assertEquals(
                "participant_identity", json.get("tracks").get(0).fieldNames().next());
        assertEquals(
                "raw/instructor/camera.webm", json.at("/tracks/0/relative_path").asText());
        assertTrue(Files.isDirectory(tempDir.resolve("269/final")));
        try (var files = Files.list(target.getParent())) {
            assertEquals(
                    List.of("tracks.json"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void 같은_경로를_다시_저장하면_완성된_JSON_하나로_교체한다() throws Exception {
        FinalizationManifestFileAdapter adapter = adapter();
        adapter.store(SESSION_ID, manifest("raw/first.webm"));

        adapter.store(SESSION_ID, manifest("raw/second.webm"));

        Path target = tempDir.resolve("269/manifest/tracks.json");
        JsonNode json = new ObjectMapper().readTree(target.toFile());
        assertEquals("raw/second.webm", json.at("/tracks/0/relative_path").asText());
        try (var files = Files.list(target.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void 같은_manifest는_항상_같은_JSON_바이트를_만든다() throws Exception {
        FinalizationManifestFileAdapter adapter = adapter();
        RecordingManifest manifest = manifest("raw/camera.webm");
        adapter.store(SESSION_ID, manifest);
        byte[] first = Files.readAllBytes(tempDir.resolve("269/manifest/tracks.json"));

        adapter.store(SESSION_ID, manifest);

        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(tempDir.resolve("269/manifest/tracks.json"))));
    }

    @Test
    void sessionId가_manifest와_다르면_다른_세션_경로에_쓰지_않는다() {
        FinalizationManifestFileAdapter adapter = adapter();

        assertThrows(IllegalArgumentException.class, () -> adapter.store(270L, manifest("raw/camera.webm")));
        assertFalse(Files.exists(tempDir.resolve("270")));
    }

    @Test
    void posix_환경에서는_디렉터리와_manifest에_보수적인_권한을_준다() throws Exception {
        FinalizationManifestFileAdapter adapter = adapter();
        adapter.store(SESSION_ID, manifest("raw/camera.webm"));
        Path target = tempDir.resolve("269/manifest/tracks.json");

        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ),
                    Files.getPosixFilePermissions(target));
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE),
                    Files.getPosixFilePermissions(target.getParent()));
        }
    }

    private FinalizationManifestFileAdapter adapter() {
        return new FinalizationManifestFileAdapter(new RecordingFinalizationProperties(tempDir.toString()));
    }

    private static RecordingManifest manifest(String relativePath) {
        return RecordingManifest.create(
                "269",
                STARTED_AT,
                List.of(new RecordingTrackEntry(
                        "instructor",
                        SessionParticipantRole.INSTRUCTOR,
                        TrackSource.CAMERA,
                        relativePath,
                        0,
                        60_000,
                        null)));
    }
}
