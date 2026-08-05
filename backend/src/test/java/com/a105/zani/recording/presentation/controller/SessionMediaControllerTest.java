package com.a105.zani.recording.presentation.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlQuery;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlUseCase;
import com.a105.zani.recording.application.streammedia.StreamMediaQuery;
import com.a105.zani.recording.application.streammedia.StreamMediaUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재생 응답의 전송 규약만 본다 — 인가 판정은 유스케이스 테스트가 갖는다.
 *
 * <p>{@code Range} 처리는 Spring 이 해 주지만, 그 전제가 깨지면(예: {@code Resource} 대신 byte[] 를 돌려주면) 조용히 200 전체 응답이 되어 탐색이 되지 않는다. 이
 * 설계에서 가장 확인이 필요한 지점이라 여기서 고정한다.
 */
class SessionMediaControllerTest {

    private static final long SESSION_ID = 100L;
    private static final String BODY = "0123456789";

    @TempDir
    Path dir;

    private MockMvc mockMvc;
    private StreamMediaQuery received;

    @BeforeEach
    void setUp() throws IOException {
        Path file = dir.resolve("lecture.mp4");
        Files.writeString(file, BODY);

        StreamMediaUseCase streamMedia = query -> {
            received = query;
            return file;
        };
        IssueMediaUrlUseCase issueMediaUrl = (IssueMediaUrlQuery query) -> {
            throw new UnsupportedOperationException("이 테스트는 재생 경로만 본다");
        };
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionMediaController(issueMediaUrl, streamMedia))
                .build();
    }

    @Test
    @DisplayName("Range 없이 부르면 전체를 200 으로 준다")
    void a_plain_request_returns_the_whole_file() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/media", SESSION_ID)
                        .param("expires", "1785840300")
                        .param("token", "t"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"));
    }

    @Test
    @DisplayName("Range 요청에 206 과 해당 구간만 준다 — 탐색이 되려면 필요하다")
    void a_range_request_returns_partial_content() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/media", SESSION_ID)
                        .param("expires", "1785840300")
                        .param("token", "t")
                        .header(HttpHeaders.RANGE, "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"));
    }

    @Test
    @DisplayName("질의 문자열의 자격을 그대로 유스케이스에 넘긴다")
    void it_passes_the_credential_through() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/media", SESSION_ID)
                        .param("expires", "1785840300")
                        .param("token", "signature"))
                .andExpect(status().isOk());

        assertThat(received.sessionId()).isEqualTo(SESSION_ID);
        assertThat(received.expiresAt()).isEqualTo(Instant.ofEpochSecond(1785840300L));
        assertThat(received.token()).isEqualTo("signature");
    }
}
