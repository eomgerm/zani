package com.a105.zani.audioclip.infrastructure.encoding;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP3 인코딩 검증.
 *
 * <p>MP3 는 손실 압축이라 원본 PCM 을 바이트로 대조할 수 없다. 대신 (1) 결과가 MP3 로 인식되는 형태인지, (2) 재생 시간에 걸맞은 크기인지, (3) 전사 예산 안에 인코딩이 끝나는지를 본다.
 */
class Mp3TranscriptionAudioEncoderTest {

    /** 전사에 넘기는 형식. 버퍼가 다운샘플을 끝낸 뒤의 규격이다. */
    private static final PcmAudioFormat FORMAT = PcmAudioFormat.transcription();

    private final Mp3TranscriptionAudioEncoder encoder = new Mp3TranscriptionAudioEncoder();

    /** seconds 초 분량의 440Hz 사인파. 무음이나 상수값은 인코더가 극단적으로 잘 압축해 크기 검증이 무의미해지므로 실제 음성에 가까운 신호를 쓴다. */
    private static byte[] tone(double seconds) {
        int samples = (int) (FORMAT.sampleRate() * seconds);
        byte[] pcm = new byte[samples * FORMAT.frameBytes()];
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / FORMAT.sampleRate()) * 12_000);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    @Test
    @DisplayName("MPEG 오디오 프레임 동기워드로 시작한다")
    void startsWithMpegFrameSync() {
        byte[] mp3 = encoder.encode(tone(1), FORMAT);

        assertTrue(mp3.length > 0, "인코딩 결과가 비어 있다");
        // 프레임 헤더 첫 11비트가 모두 1(동기워드). ID3 태그를 붙이지 않으므로 첫 바이트부터 나온다.
        assertEquals(0xFF, mp3[0] & 0xFF, "첫 바이트가 동기워드가 아니다");
        assertEquals(0xE0, mp3[1] & 0xE0, "두 번째 바이트 상위 3비트가 동기워드가 아니다");
    }

    @Test
    @DisplayName("MIME 타입은 audio/mpeg 다")
    void reportsMpegContentType() {
        assertEquals("audio/mpeg", encoder.contentType());
    }

    @Test
    @DisplayName("설정한 비트레이트에 맞는 크기로 나온다")
    void sizeMatchesConfiguredBitrate() {
        int seconds = 10;
        byte[] mp3 = encoder.encode(tone(seconds), FORMAT);

        long expected = (long) Mp3TranscriptionAudioEncoder.BITRATE_KBPS * 1000L / 8L * seconds;
        // CBR 이라 예측값에 근접해야 한다. 프레임 정렬과 Info 태그 때문에 20% 여유를 둔다.
        assertTrue(
                mp3.length > expected * 0.8 && mp3.length < expected * 1.2,
                "기대 %d 바이트 근처여야 하는데 %d 바이트다".formatted(expected, mp3.length));
    }

    @Test
    @DisplayName("PCM 보다 훨씬 작다 — MP3 로 바꾼 목적")
    void isMuchSmallerThanPcm() {
        byte[] pcm = tone(10);
        byte[] mp3 = encoder.encode(pcm, FORMAT);

        // 16kHz 16bit 모노는 256kbps 다. 64kbps 로 줄이므로 4배 가까이 작아야 한다.
        assertTrue(mp3.length * 3 < pcm.length, "PCM %d → MP3 %d, 기대만큼 줄지 않았다".formatted(pcm.length, mp3.length));
    }

    @Test
    @DisplayName("빈 입력에도 터지지 않는다")
    void handlesEmptyInput() {
        byte[] mp3 = encoder.encode(new byte[0], FORMAT);

        assertTrue(mp3.length < 1024, "빈 입력인데 %d 바이트가 나왔다".formatted(mp3.length));
    }

    @Test
    @DisplayName("여러 번 호출해도 같은 결과 — 인코더 상태가 새지 않는다")
    void isStatelessAcrossCalls() {
        byte[] pcm = tone(2);

        byte[] first = encoder.encode(pcm, FORMAT);
        byte[] second = encoder.encode(pcm, FORMAT);

        // LameEncoder 는 스레드 안전하지 않아 호출마다 새로 만든다. 인스턴스를 재사용하면 두 번째가 달라진다.
        assertEquals(first.length, second.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second);
    }

    @Test
    @DisplayName("창 전체(5분) 인코딩이 전사 예산을 잡아먹지 않는다")
    void encodesFullWindowWellWithinBudget() {
        byte[] pcm = tone(Duration.ofMinutes(5).toSeconds());

        long startedAt = System.nanoTime();
        byte[] mp3 = encoder.encode(pcm, FORMAT);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        // 전사 timeout 이 30초이고 전사 자체가 25초 안팎을 쓴다. 인코딩이 그 예산을 잠식하면 안 된다.
        // 실측은 약 800ms 다. 시간 단언은 부하가 걸린 CI 에서 흔들리므로, 정상 값 근처가 아니라 "명백한 파국"
        // 수준인 10초로 잡는다. 이 정도면 오탐은 사실상 없고, 인코딩이 10배 이상 느려지는 회귀는 여전히 잡힌다.
        System.out.printf("5분 MP3 인코딩: %dms, %d bytes%n", elapsedMs, mp3.length);
        assertTrue(elapsedMs < 10_000, "5분 인코딩에 %dms 걸렸다 — 전사 예산을 침범한다".formatted(elapsedMs));
        // Whisper 업로드 상한 25MB 에 크게 못 미쳐야 한다.
        assertTrue(mp3.length < 25 * 1024 * 1024, "Whisper 상한을 넘는다: %d bytes".formatted(mp3.length));
    }
}
