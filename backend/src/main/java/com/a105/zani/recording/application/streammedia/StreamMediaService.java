package com.a105.zani.recording.application.streammedia;

import java.nio.file.Path;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.exception.InvalidMediaAccessException;
import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;

/**
 * 서명된 주소로 들어온 재생 요청을 검증한다.
 *
 * <p>이 경로에는 인증 주체가 없다 — {@code <video>} 가 Authorization 헤더를 싣지 못해 자격이 주소 안에 들어 있기 때문이다. 그래서 <b>서명이 곧 권한</b>이고, 서명을 통과하지
 * 못한 요청은 파일이 있는지조차 알려주지 않는다. 검증을 파일 조회보다 먼저 두는 이유다(FRD §21 "파일 다운로드 URL에서도 권한 확인").
 */
@Service
@RequiredArgsConstructor
public class StreamMediaService implements StreamMediaUseCase {

    private final MediaAccessPort mediaAccessPort;
    private final LectureMediaPort lectureMediaPort;

    @Override
    public Path locate(StreamMediaQuery query) {
        if (query.expiresAt() == null
                || query.token() == null
                || !mediaAccessPort.matches(query.sessionId(), query.expiresAt(), query.token())) {
            throw new InvalidMediaAccessException();
        }

        return lectureMediaPort.findLectureRecording(query.sessionId()).orElseThrow(MediaNotReadyException::new);
    }
}
