package com.a105.zani.audioclip.infrastructure.encoding;

import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFormat;

import de.sciss.jump3r.lowlevel.LameEncoder;
import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * LAME 의 순수 Java 포트(jump3r)로 PCM 을 MP3 로 인코딩한다.
 *
 * <p>순수 Java 라 네이티브 라이브러리나 ffmpeg 바이너리를 런타임 이미지에 넣지 않는다. 다만 jump3r 의 공개 API 가 {@code javax.sound.sampled.AudioFormat} 을
 * 요구하므로 런타임에 {@code java.desktop} 모듈이 있어야 한다(Dockerfile 이 빌드 시점에 검증한다).
 *
 * <p>{@link LameEncoder} 는 내부에 인코딩 상태를 들고 있어 스레드 안전하지 않다. 호출마다 새로 만들어 쓰고 닫는다. 호출 빈도가 코칭 트리거 주기(분 단위)라 생성 비용은 문제되지 않는다.
 */
@Component
public class Mp3TranscriptionAudioEncoder implements TranscriptionAudioEncoder {

    static final String CONTENT_TYPE = "audio/mpeg";

    /**
     * 16kHz 모노 음성 기준. MPEG-2 Layer III 가 지원하는 값이며 5분이 약 2.4MB 로 Whisper 업로드 상한(25MB)에 크게 여유가 있다. 더 낮추면 용량은 줄지만 전사 정확도를
     * 해칠 수 있어, 상한이 충분한 이상 낮출 이유가 없다.
     */
    static final int BITRATE_KBPS = 64;

    @Override
    public byte[] encode(byte[] pcm, PcmAudioFormat format) {
        // Egress 가 보내는 것도 버퍼가 떠내는 것도 s16le 라 signed=true, bigEndian=false 로 고정한다.
        AudioFormat source =
                new AudioFormat(format.sampleRate(), format.bitsPerSample(), format.channels(), true, false);
        // 마지막 인자가 VBR 여부. 전사용이라 크기 예측이 쉬운 CBR 로 둔다.
        LameEncoder encoder =
                new LameEncoder(source, BITRATE_KBPS, channelMode(format), LameEncoder.QUALITY_MIDDLE, false);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(estimateSize(pcm.length, format));
            byte[] mp3 = new byte[encoder.getMP3BufferSize()];
            int chunk = pcmChunkSize(encoder, format);
            for (int offset = 0; offset < pcm.length; offset += chunk) {
                int length = Math.min(chunk, pcm.length - offset);
                out.write(mp3, 0, encoder.encodeBuffer(pcm, offset, length, mp3));
            }
            // 마지막 프레임과 VBR/Info 태그를 내보낸다. 이걸 빠뜨리면 끝부분이 잘린 MP3 가 나온다.
            out.write(mp3, 0, encoder.encodeFinish(mp3));
            return out.toByteArray();
        } finally {
            encoder.close();
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    private static int channelMode(PcmAudioFormat format) {
        return format.channels() == 1 ? LameEncoder.CHANNEL_MODE_MONO : LameEncoder.CHANNEL_MODE_JOINT_STEREO;
    }

    /** 한 번에 넘길 PCM 바이트 수. {@code encodeBuffer} 는 프레임 중간에서 끊긴 입력을 받으면 채널이 뒤바뀌므로 프레임 경계로 내림한다. */
    private static int pcmChunkSize(LameEncoder encoder, PcmAudioFormat format) {
        int frameBytes = format.frameBytes();
        int chunk = encoder.getPCMBufferSize() - (encoder.getPCMBufferSize() % frameBytes);
        return chunk <= 0 ? frameBytes : chunk;
    }

    /** CBR 이므로 재생 시간에서 출력 크기가 거의 정해진다. 재할당을 피하려고 여유를 조금 얹는다. */
    private static int estimateSize(int pcmBytes, PcmAudioFormat format) {
        long bytesPerSecond = BITRATE_KBPS * 1000L / 8L;
        long seconds = pcmBytes / Math.max(1, format.bytesPerSecond()) + 1;
        return (int) Math.min(Integer.MAX_VALUE, bytesPerSecond * seconds + 4096);
    }
}
