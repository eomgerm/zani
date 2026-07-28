package com.a105.zani.coach.application.port;

/**
 * 전사 결과.
 *
 * @param text 전사 텍스트. 무음·빈 오디오면 빈 문자열일 수 있다
 * @param elapsedMs 전사 호출에 걸린 시간. 메트릭으로 남긴다 (S15P11A105-203)
 */
public record TranscriptResult(String text, long elapsedMs) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
