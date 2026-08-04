package com.a105.zani.postclass.application.port;

import java.nio.file.Path;

import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;

/**
 * 오디오 파일 하나를 전사한다(S15P11A105-247).
 *
 * <p><b>{@code audioclip} 의 {@code AudioTranscriptionPort} 를 재사용하지 않는 이유.</b> 그 포트의 계약이 이렇게 적혀 있다.
 *
 * <blockquote>
 *
 * 구현은 스트림을 디스크에 기록해서는 안 된다 — "전사가 끝나면 오디오를 즉시 폐기한다"는 요구사항의 가장 강한 형태는 애초에 저장하지 않는 것이다.
 *
 * </blockquote>
 *
 * <p>그것은 실시간 코칭의 {@code DATA-006}(전사 후 즉시 폐기)을 인터페이스에 못박은 것이다. 사후 전사는 <b>의도적으로 보존된 녹화 파일</b>을 읽으므로 같은 인터페이스에 상반된 프라이버시
 * 계약 둘이 얹힌다. 그리고 그 포트는 {@code String} 만 돌려주어 타임스탬프를 실을 자리가 없다.
 *
 * <p>여기서는 파일 경로를 받는다. 청크는 이미 디스크에 있고(읽기 전용 원본을 자른 산출물), 요청에 Content-Length 를 실어야 해서 어차피 전량을 읽는다.
 */
public interface PostClassTranscriptionPort {

    /**
     * @param audio 전사할 오디오 파일. 작업 디렉터리 안의 청크다
     * @param contentType 업로드할 MIME 타입. 원본을 그대로 올리므로 보통 {@code audio/ogg} 다
     * @return 넘긴 오디오 기준 상대 시각 세그먼트
     * @throws PostClassTranscriptionFailedException 호출 실패·응답 계약 위반
     */
    TranscriptionResult transcribe(Path audio, String contentType);
}
