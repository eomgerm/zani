package com.a105.zani.session.application.port;

/** 미디어 서버(LiveKit) 접속 자격증명. 로그·응답에 노출하지 않는다. */
public record MediaServerCredentials(String serverUrl, String apiKey, String apiSecret) {

    public boolean isConfigured() {
        return isPresent(serverUrl) && isPresent(apiKey) && isPresent(apiSecret);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
