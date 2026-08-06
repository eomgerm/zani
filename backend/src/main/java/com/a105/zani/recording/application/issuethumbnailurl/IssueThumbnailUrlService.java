package com.a105.zani.recording.application.issuethumbnailurl;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;

/**
 * 내 수업 목록의 카드에 실을 썸네일 접근 주소를 발급한다.
 *
 * <p><b>권한은 여기서 다시 보지 않는다.</b> 이 유스케이스를 부르는 곳은 이미 "내가 참여한 세션"으로 좁혀진 목록이라, 녹화 주소 발급({@code IssueMediaUrlService})처럼 참여자
 * 판정을 반복하면 목록 한 번에 세션 수만큼 같은 답을 다시 묻는다. 참여자 확인이 없는 경로에서 이 유스케이스를 부르는 것이 곧 잘못이다.
 *
 * <p><b>파일 존재가 곧 준비 판정이다.</b> 썸네일은 최종 병합이 끝난 세션에만 생기므로 세션 상태를 따로 볼 필요가 없다 — 진행 중인 수업은 파일이 없어 자연히 비어 있음으로 내려간다. 없음을 오류로
 * 만들지 않는 것은 카드가 썸네일 없이도 그려져야 하기 때문이다(대표 프레임 추출은 best-effort 다).
 */
@Service
@RequiredArgsConstructor
public class IssueThumbnailUrlService implements IssueThumbnailUrlUseCase {

    private final LectureMediaPort lectureMediaPort;
    private final MediaAccessPort mediaAccessPort;

    @Override
    public Optional<String> issue(long sessionId) {
        if (lectureMediaPort.findLectureThumbnail(sessionId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mediaAccessPort.issueThumbnail(sessionId).url());
    }
}
