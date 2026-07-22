package com.a105.zani.contracts;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 실제로 뜬 CreateSession 컨트롤러의 응답이 zani.yaml 계약과 일치하는지 검증한다.
 * mock 인증 대신 TokenProvider로 실제 서명된 JWT를 발급해 써서, 실제 보안 필터 체인과
 * 충돌 없이 요청 헤더 기준으로 계약을 검증할 수 있게 한다.
 * 로컬 MySQL, Redis가 떠 있어야 통과한다.
 */
@SpringBootTest
class CreateSessionContractTest {

    private static final String SPEC_PATH = "contracts/openapi/zani.yaml";
    private static final long INSTRUCTOR_ID = -200L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private SessionActivationLockPort activationLockPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        sessionJpaRepository.findAll().stream()
                .filter(session -> session.getInstructorId().equals(INSTRUCTOR_ID))
                .forEach(sessionJpaRepository::delete);
        activationLockPort.release(INSTRUCTOR_ID);
    }

    @Test
    void createSessionResponseMatchesTheOpenApiContract() throws Exception {
        String accessToken = tokenProvider.issueAccessToken(String.valueOf(INSTRUCTOR_ID)).value();

        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"계약 검증 테스트 세션\"}"))
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(SPEC_PATH));
    }
}
