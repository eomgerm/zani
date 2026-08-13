package com.a105.zani.recording.infrastructure.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailJpegCacheTest {

    private static final long SESSION_ID = 100L;

    @TempDir
    Path mediaDir;

    @TempDir
    Path cacheDir;

    /** {@code @TempDir} 는 필드 초기화 뒤에 주입되므로 필드 이니셜라이저에서 쓰면 null 이다. */
    private ThumbnailJpegCache cache() {
        return new ThumbnailJpegCache(cacheDir, 800, 0.8f);
    }

    /** 병합 워커 산출물과 같은 1280x720. 단색이면 PNG 가 지나치게 작아지므로 화소마다 값이 다른 무늬를 채운다. */
    private Path writeFrame(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 31 + y * 17) % 255 << 16) | ((x * 7) % 255 << 8) | (y * 13) % 255);
            }
        }
        Path file = mediaDir.resolve("frame-50.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    @Test
    @DisplayName("원본 프레임을 표시 폭 이하의 JPEG 로 줄여 돌려준다")
    void it_scales_the_frame_down_to_display_width() throws IOException {
        Path original = writeFrame(1280, 720);

        Path served = cache().deliverable(SESSION_ID, original);

        assertThat(served.getFileName().toString()).isEqualTo(SESSION_ID + ".jpg");
        BufferedImage scaled = ImageIO.read(served.toFile());
        assertThat(scaled.getWidth()).isEqualTo(800);
        assertThat(scaled.getHeight()).isEqualTo(450);
        assertThat(Files.size(served)).isLessThan(Files.size(original));
    }

    @Test
    @DisplayName("표시 폭보다 작은 원본은 키우지 않는다 — 업스케일은 용량만 늘린다")
    void a_small_frame_is_not_upscaled() throws IOException {
        Path original = writeFrame(400, 225);

        BufferedImage scaled =
                ImageIO.read(cache().deliverable(SESSION_ID, original).toFile());

        assertThat(scaled.getWidth()).isEqualTo(400);
    }

    @Test
    @DisplayName("두 번째 요청은 변환 없이 캐시를 그대로 쓴다")
    void a_second_request_reuses_the_cache() throws IOException {
        Path original = writeFrame(1280, 720);
        Path served = cache().deliverable(SESSION_ID, original);

        // 캐시 내용물을 표식으로 바꿔 두면, 재변환이 일어났는지(표식이 사라졌는지)로 재사용 여부가 드러난다.
        Files.write(served, new byte[] {1, 2, 3});

        assertThat(cache().deliverable(SESSION_ID, original)).isEqualTo(served);
        assertThat(Files.readAllBytes(served)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("병합을 다시 돌려 프레임이 새로 써지면 캐시도 다시 만든다")
    void a_refinalized_frame_invalidates_the_cache() throws IOException {
        Path original = writeFrame(1280, 720);
        Path served = cache().deliverable(SESSION_ID, original);
        Files.write(served, new byte[] {1, 2, 3});

        // 재병합으로 원본이 캐시보다 나중에 써진 상황. 파일 시스템의 시각 해상도에 기대지 않도록 명시적으로 미래를 준다.
        Files.setLastModifiedTime(original, FileTime.from(Instant.now().plusSeconds(60)));

        Path reconverted = cache().deliverable(SESSION_ID, original);
        assertThat(Files.readAllBytes(reconverted)).isNotEqualTo(new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("이미지로 읽을 수 없는 원본은 그대로 돌려준다 — 느린 썸네일이 안 보이는 썸네일보다 낫다")
    void an_unreadable_frame_falls_back_to_the_original() throws IOException {
        Path original = mediaDir.resolve("frame-50.png");
        Files.writeString(original, "png 이 아닌 무엇");

        assertThat(cache().deliverable(SESSION_ID, original)).isEqualTo(original);
    }
}
