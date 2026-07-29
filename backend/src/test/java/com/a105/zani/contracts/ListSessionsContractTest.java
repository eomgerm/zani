package com.a105.zani.contracts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제로 뜬 GET /api/v1/sessions 응답이 zani.yaml 계약과 일치하는지 검증한다. 로컬 MySQL, Redis가 떠 있어야 통과한다. */
@SpringBootTest
class ListSessionsContractTest {

    private static final String SPEC_PATH = "contracts/openapi/zani.yaml";
    private static final long INSTRUCTOR_ID = -300L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private SessionActivationLockPort activationLockPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        insertInstructorIfAbsent();
        // 앞선 실행이 정리를 못 마치고 죽었으면 3시간짜리 잠금이 남아 이 테스트가 409 를 받는다.
        activationLockPort.release(INSTRUCTOR_ID);
    }

    @AfterEach
    void tearDown() {
        // 자식 행을 먼저 지운다. 세션 생성이 강사 참가자 행을 함께 만들므로, 세션부터 지우면 FK 위반으로 정리가 끊기고
        // 그 뒤의 잠금 해제가 실행되지 않아 다음 실행이 "이미 진행 중인 수업" 으로 막힌다.
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (SELECT id FROM sessions WHERE host_member_id = ?)",
                INSTRUCTOR_ID);
        jdbcTemplate.update(
                "DELETE FROM session_status_changes WHERE session_id IN (SELECT id FROM sessions WHERE host_member_id = ?)",
                INSTRUCTOR_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE host_member_id = ?", INSTRUCTOR_ID);
        activationLockPort.release(INSTRUCTOR_ID);
        jdbcTemplate.update("DELETE FROM members WHERE id = ?", INSTRUCTOR_ID);
    }

    private void insertInstructorIfAbsent() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                INSTRUCTOR_ID,
                "list-sessions-contract-test-subject",
                "list-sessions-contract-test@zani.local",
                "세션 목록 계약 테스트 강사");
    }

    @Test
    void listSessionsResponseMatchesTheOpenApiContract() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(INSTRUCTOR_ID)).value();

        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"목록 계약 검증용 세션\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(SPEC_PATH));
    }
}
