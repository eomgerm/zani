package com.a105.zani.recording.application.streammedia;

import java.nio.file.Path;

public interface StreamThumbnailUseCase {

    /** 자격을 검증하고 내보낼 썸네일 파일의 위치를 확정한다. 바이트 전송은 표현 계층이 맡는다. */
    Path locate(StreamMediaQuery query);
}
