package com.a105.zani.audioclip.infrastructure.gms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.a105.zani.common.infrastructure.gms.GmsClientConfig;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MockRestServiceServer가 보지 못하는 실제 HTTP multipart framing을 검증한다. */
class GmsTranscriptionRequestFramingTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsMultipartWithContentLengthInsteadOfChunkedEncoding() throws IOException {
        AtomicReference<String> contentLength = new AtomicReference<>();
        AtomicReference<String> transferEncoding = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        GmsProperties properties =
                properties("http://127.0.0.1:" + server.getAddress().getPort());
        RestClient client = new GmsClientConfig().gmsTranscriptionRestClient(properties);
        new GmsAudioTranscriptionAdapter(client, properties)
                .transcribe(new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xfb, 0x10, 0}), "audio/mpeg");

        assertTrue(Long.parseLong(contentLength.get()) > 0);
        assertNull(transferEncoding.get());
        assertTrue(body.get().contains("name=\"file\""));
        assertTrue(body.get().contains("filename=\"audio.mp3\""));
        assertTrue(body.get().contains("name=\"model\""));
        assertTrue(body.get().contains("whisper-1"));
        assertTrue(body.get().contains("name=\"language\""));
        assertTrue(body.get().contains("ko"));
    }

    private static GmsProperties properties(String baseUrl) {
        return new GmsProperties(
                baseUrl,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                "ko",
                "gpt-5.4-mini",
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60));
    }
}
