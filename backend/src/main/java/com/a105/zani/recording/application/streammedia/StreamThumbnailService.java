package com.a105.zani.recording.application.streammedia;

import java.nio.file.Path;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.exception.InvalidMediaAccessException;
import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;

/**
 * 서명된 주소로 들어온 썸네일 요청을 검증한다.
 *
 * <p>{@code <img>} 도 {@code <video>} 처럼 Authorization 헤더를 싣지 못해 재생 경로({@link StreamMediaService})와 같은 규칙을 따른다 — 서명이 곧
 * 권한이고, 서명을 통과하지 못한 요청은 파일이 있는지조차 알려주지 않는다. 자격도 같은 것을 쓴다: 썸네일은 녹화에서 잘라낸 한 프레임이라 녹화를 볼 수 있는 사람과 볼 수 없는 사람이 정확히 같다.
 */
@Service
@RequiredArgsConstructor
public class StreamThumbnailService implements StreamThumbnailUseCase {

    private final MediaAccessPort mediaAccessPort;
    private final LectureMediaPort lectureMediaPort;

    @Override
    public Path locate(StreamMediaQuery query) {
        if (query.expiresAt() == null
                || query.token() == null
                || !mediaAccessPort.matches(query.sessionId(), query.expiresAt(), query.token())) {
            throw new InvalidMediaAccessException();
        }

        return lectureMediaPort
                .findLectureThumbnailForServing(query.sessionId())
                .orElseThrow(MediaNotReadyException::new);
    }
}
