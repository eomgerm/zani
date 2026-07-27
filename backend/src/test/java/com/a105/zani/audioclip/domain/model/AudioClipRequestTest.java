package com.a105.zani.audioclip.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioClipRequestTest {

    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void 생성_직후에는_PENDING이고_TTL만큼_뒤에_만료된다() {
        AudioClipRequest request = AudioClipRequest.create(1L, 10L, T0);

        assertEquals(AudioClipRequestStatus.PENDING, request.status());
        assertFalse(request.isResolved());
        assertEquals(T0.plus(AudioClipRequest.REQUEST_TTL), request.expiresAt());
    }

    @Test
    void 만료는_경계_시각을_포함한다() {
        AudioClipRequest request = AudioClipRequest.create(1L, 10L, T0);

        assertFalse(request.isExpired(request.expiresAt().minusMillis(1)));
        assertTrue(request.isExpired(request.expiresAt()));
    }

    @Test
    void 업로드_완료는_전사_텍스트를_남기고_실패_사유를_지운다() {
        AudioClipRequest request = AudioClipRequest.create(1L, 10L, T0);
        request.fail(AudioClipFailureReason.UPLOAD_FAILED);

        request.completeUpload("전사 텍스트");

        assertEquals(AudioClipRequestStatus.UPLOADED, request.status());
        assertEquals("전사 텍스트", request.transcriptText());
        assertNull(request.failureReason());
        assertTrue(request.isResolved());
    }

    @Test
    void 실패는_사유를_남긴다() {
        AudioClipRequest request = AudioClipRequest.create(1L, 10L, T0);

        request.fail(AudioClipFailureReason.MICROPHONE_OFF);

        assertEquals(AudioClipRequestStatus.FAILED, request.status());
        assertEquals(AudioClipFailureReason.MICROPHONE_OFF, request.failureReason());
        assertTrue(request.isResolved());
    }
}
