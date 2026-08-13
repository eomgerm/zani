package com.a105.zani.recording.infrastructure.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 병합 워커가 뽑아 둔 대표 프레임(1280x720 PNG, 장당 0.5~1MB)을 카드 표시 크기로 줄인 JPEG 로 바꿔 둔다.
 *
 * <p>원본을 그대로 내보내면 목록 화면이 카드 수에 비례해 무거워진다(내 강의실 LCP 3.5s 의 주범). 프레임은 병합이 끝나는 순간 확정되므로 한 번 줄인 결과를 디스크에 캐시하고, 이후 요청은 캐시를
 * 그대로 서빙한다 — 기존 세션도 첫 요청 때 변환되므로 백필이 필요 없다.
 *
 * <p><b>캐시는 미디어 마운트가 아니라 컨테이너 로컬 tmp 에 둔다.</b> 서빙 마운트(/recordings)는 읽기 전용이고, 쓰기 가능한 /finalized 는 병합 오케스트레이션만 쓰기로 계약되어
 * 있다(compose.yaml). tmp 캐시는 재배포 때 사라지지만 변환이 한 번 수십 ms 라 다시 채우는 비용이 없다시피 하다.
 *
 * <p><b>WebP 가 아니라 JPEG 인 이유</b>: JDK ImageIO 는 WebP 인코딩이 없어 네이티브 라이브러리를 들여야 하는데, 순수 Java 를 지키려고 ffmpeg 대신 jump3r 를 고른
 * 오디오 인코더({@code Mp3TranscriptionAudioEncoder})와 같은 이유로 피한다. 표시 크기로 줄인 JPEG 만으로 원본 대비 90% 이상이 줄어 충분하다.
 *
 * <p>변환에 실패하면(깨진 원본, 캐시 디스크 문제) 원본 PNG 를 그대로 돌려준다 — 썸네일이 느린 것이 안 보이는 것보다 낫다.
 */
@Slf4j
@Component
public class ThumbnailJpegCache {

    /**
     * 카드 썸네일 표시 폭 555 CSS px × DPR 2 = 1110 실픽셀이 상한이지만, object-cover 로 걸치는 그림이라 800이면 육안 차이 없이 용량이 절반 아래로 준다. 16:9 원본
     * 기준 800x450.
     */
    private static final int MAX_WIDTH = 800;

    /** 강의 화면(슬라이드·판서) 기준으로 열화가 보이지 않는 최저선. 더 내리면 글자 주변 링잉이 보이기 시작한다. */
    private static final float JPEG_QUALITY = 0.8f;

    private final Path cacheRoot;
    private final int maxWidth;
    private final float quality;

    public ThumbnailJpegCache() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "zani-thumbnail-cache"), MAX_WIDTH, JPEG_QUALITY);
    }

    ThumbnailJpegCache(Path cacheRoot, int maxWidth, float quality) {
        this.cacheRoot = cacheRoot;
        this.maxWidth = maxWidth;
        this.quality = quality;
    }

    /**
     * 서빙할 썸네일 파일을 돌려준다 — 캐시가 신선하면 캐시, 아니면 변환해서 캐시, 변환이 안 되면 원본.
     *
     * <p>신선도는 수정 시각으로 판단한다. 캐시 파일의 수정 시각을 원본과 같게 맞춰 두므로, 병합을 다시 돌려 프레임이 새로 써지면 캐시가 저절로 낡은 것이 되어 다음 요청 때 다시 변환된다. 컨테이너와
     * 호스트 마운트의 시계가 달라도 "지금 시각"이 아니라 원본의 시각을 복사하므로 영원히 재변환하는 일이 없다.
     */
    public Path deliverable(long sessionId, Path original) {
        Path cached = cacheRoot.resolve(sessionId + ".jpg");
        try {
            var originalTime = Files.getLastModifiedTime(original);
            if (Files.isRegularFile(cached) && Files.getLastModifiedTime(cached).compareTo(originalTime) >= 0) {
                return cached;
            }

            BufferedImage source = ImageIO.read(original.toFile());
            if (source == null) {
                log.warn("썸네일 원본을 이미지로 읽을 수 없어 원본을 그대로 서빙합니다. sessionId={}, file={}", sessionId, original);
                return original;
            }

            Files.createDirectories(cacheRoot);
            // 동시 첫 요청이 겹치면 둘 다 변환하지만, 임시 파일을 원자적으로 옮기므로 반쯤 쓰인 파일이 보이는 일은 없다.
            Path tmp = Files.createTempFile(cacheRoot, sessionId + "-", ".jpg.tmp");
            try {
                writeScaledJpeg(source, tmp);
                Files.setLastModifiedTime(tmp, originalTime);
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return cached;
        } catch (IOException | RuntimeException e) {
            log.warn("썸네일 변환에 실패해 원본을 그대로 서빙합니다. sessionId={}", sessionId, e);
            return original;
        }
    }

    private void writeScaledJpeg(BufferedImage source, Path target) throws IOException {
        int width = Math.min(maxWidth, source.getWidth());
        int height = Math.max(1, Math.round((float) source.getHeight() * width / source.getWidth()));

        // JPEG 에는 알파가 없다. ARGB 를 바로 쓰면 ImageIO 가 색을 뒤틀어 놓으므로 불투명 캔버스에 옮겨 그린다.
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer 가 없는 JVM 입니다");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new IIOImage(canvas, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
