package com.a105.zani.audioclip.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 오디오 클립 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터를 통과시켜 인증·강사 검증의 HTTP 상태 매핑을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AudioClipControllerTest {

    private static final long NON_MEMBER_ID = -900L;
    private static final long MISSING_SESSION_ID = 9_000_900L;
    private static final long CLIP_ID = 1L;
    private static final String META_JSON = """
            {"capturedFrom":"2026-07-27T00:00:00Z","capturedTo":"2026-07-27T00:03:00Z",
             "durationMs":180000,
             "segments":[{"from":"2026-07-27T00:00:00Z","to":"2026-07-27T00:03:00Z"}]}
            """;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    // "디스크 미기록" 보장의 핵심 설정. 이 값이 풀리면 Tomcat이 업로드를 java.io.tmpdir에 파일로 떨군다.
    @Value("${spring.servlet.multipart.file-size-threshold}")
    private DataSize fileSizeThreshold;

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void multipart는_상한과_같은_인메모리_임계치를_유지해야_한다() {
        assertEquals(DataSize.ofMegabytes(8), fileSizeThreshold);
        assertEquals(DataSize.ofMegabytes(8), maxFileSize);
    }

    @Test
    void 업로드에_인증이_없으면_401() throws Exception {
        mockMvc.perform(multipart("/api/v1/sessions/{sessionId}/audio-clips/{clipId}", MISSING_SESSION_ID, CLIP_ID)
                        .file(audioPart())
                        .file(metaPart()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 업로드에_세션_강사가_아니면_403() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(multipart("/api/v1/sessions/{sessionId}/audio-clips/{clipId}", MISSING_SESSION_ID, CLIP_ID)
                        .file(audioPart())
                        .file(metaPart())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 실패_보고에_인증이_없으면_401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/audio-clips/{clipId}/failure", MISSING_SESSION_ID, CLIP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"MICROPHONE_OFF\",\"availableMs\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 실패_보고에_세션_강사가_아니면_403() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/audio-clips/{clipId}/failure", MISSING_SESSION_ID, CLIP_ID)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"MICROPHONE_OFF\",\"availableMs\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 실패_보고_본문이_비어_있으면_400() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/audio-clips/{clipId}/failure", MISSING_SESSION_ID, CLIP_ID)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private MockMultipartFile audioPart() {
        return new MockMultipartFile("audio", "clip.webm", "audio/webm;codecs=opus", new byte[] {1, 2, 3});
    }

    private MockMultipartFile metaPart() {
        return new MockMultipartFile("meta", "meta", MediaType.APPLICATION_JSON_VALUE, META_JSON.getBytes());
    }
}
