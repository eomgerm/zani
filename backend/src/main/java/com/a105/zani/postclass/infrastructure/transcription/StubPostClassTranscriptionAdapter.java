package com.a105.zani.postclass.infrastructure.transcription;

import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;
import com.a105.zani.postclass.application.port.PostClassTranscriptionPort;
import com.a105.zani.postclass.application.port.TranscriptionResult;

/**
 * {@code gms.mock-enabled=true} 일 때의 사후 전사 어댑터. <b>전사하지 않고 거절한다.</b>
 *
 * <p><b>왜 필요한가.</b> 실제 어댑터({@code GmsPostClassTranscriptionAdapter})는 {@code mock-enabled=false} 에서만 등록된다. 짝이 없으면 mock
 * 모드에서 {@link PostClassTranscriptionPort} 빈이 아예 없어, 이 포트를 주입받는 오케스트레이션 때문에 컨텍스트가 뜨지 않는다 —
 * {@code StubAudioTranscriptionAdapter} 가 실시간 전사에서 하는 역할과 같다.
 *
 * <p><b>왜 가짜 결과를 만들지 않는가.</b> 실시간 쪽 스텁은 고정 문구를 돌려주지만 여기서는 그럴 수 없다. 사후 전사 결과는 {@code transcripts} 한 행에 저장되고 하류 8건이 그것을
 * 읽는다. 가짜 세그먼트를 저장하면 데모 시드의 전사를 덮어써 시연 화면이 "[전사 미연동]" 만 보여 준다. 그리고 오케스트레이션은 GMS 가 보고한 길이를 CSV 구간과 대조하는데, 스텁은 그 길이를 알
 * 방법이 없어 어떤 값을 넣어도 검증에 걸린다.
 *
 * <p>재시도 불가로 던진다. mock 모드가 켜져 있는 동안은 몇 번을 다시 해도 같다. 로컬에서 이 예외를 봤다면 원본 OGG 와 {@code GMS_API_KEY} 를 준비하고
 * {@code GMS_MOCK_ENABLED=false} 로 올려야 한다는 뜻이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class StubPostClassTranscriptionAdapter implements PostClassTranscriptionPort {

    @Override
    public TranscriptionResult transcribe(Path audio, String contentType) {
        log.warn(
                "Post-class transcription is not wired in mock mode, refusing to fabricate a transcript: file={}",
                audio.getFileName());
        throw new PostClassTranscriptionFailedException(false);
    }
}
