package com.a105.zani.recording.infrastructure.filesystem;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.finalizemanifest.FinalizationManifestStoreException;
import com.a105.zani.recording.application.finalizemanifest.FinalizationManifestStorePort;
import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationProperties;

/** {@code {outputRoot}/{sessionId}/manifest/tracks.json}을 임시 파일에서 원자적으로 교체한다. */
@Component
public class FinalizationManifestFileAdapter implements FinalizationManifestStorePort {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);

    private final Path outputRoot;
    private final ObjectMapper objectMapper;

    public FinalizationManifestFileAdapter(RecordingFinalizationProperties properties) {
        this.outputRoot = Path.of(properties.outputRoot()).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public void store(Long sessionId, RecordingManifest manifest) {
        if (sessionId == null || sessionId <= 0 || !String.valueOf(sessionId).equals(manifest.sessionId())) {
            throw new IllegalArgumentException("manifest session id does not match");
        }
        Path sessionRoot = outputRoot.resolve(String.valueOf(sessionId)).normalize();
        if (!sessionRoot.startsWith(outputRoot)) {
            throw new IllegalArgumentException("session path escapes output root");
        }
        Path manifestDirectory = sessionRoot.resolve("manifest");
        Path finalDirectory = sessionRoot.resolve("final");
        Path target = manifestDirectory.resolve("tracks.json");
        Path temporary = null;
        try {
            createDirectory(sessionRoot);
            createDirectory(manifestDirectory);
            createDirectory(finalDirectory);
            temporary = Files.createTempFile(manifestDirectory, ".tracks-", ".tmp");
            setPermissionsIfSupported(temporary, FILE_PERMISSIONS);
            Files.write(
                    temporary,
                    objectMapper.writeValueAsBytes(manifest),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveAtomically(temporary, target);
            setPermissionsIfSupported(target, FILE_PERMISSIONS);
        } catch (IOException failure) {
            throw new FinalizationManifestStoreException(failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 원래 실패를 가리지 않는다. 다음 실행도 고유 임시 파일을 사용한다.
                }
            }
        }
    }

    private static void createDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPermissionsIfSupported(directory, DIRECTORY_PERMISSIONS);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // 비원자 fallback은 독자가 절반짜리 JSON을 볼 수 있으므로 지원하지 않는다. 운영 경로는 같은 파일시스템이다.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void setPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
