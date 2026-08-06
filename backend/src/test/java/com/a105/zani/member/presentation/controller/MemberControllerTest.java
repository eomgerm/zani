package com.a105.zani.member.presentation.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 정보 조회 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터 체인을 통과시켜 인증 주체 파싱과 예외별 HTTP 상태·응답 형식을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과하며, 테스트
 * 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class MemberControllerTest {

    private static final long MEMBER_ID = 9_600_001L;
    private static final long MISSING_MEMBER_ID = 9_600_900L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /** Hibernate가 UTC로 저장하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String tokenOf(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private void insertMember() {
        LocalDateTime now = utc(Instant.now());
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, profile_image_url,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                MEMBER_ID,
                "google-" + MEMBER_ID,
                MEMBER_ID + "@example.com",
                "내 정보 테스트 회원",
                "https://pic.example.com/me.png",
                now,
                now);
    }

    @Test
    void respondsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void returnsTheProfileOfTheAuthenticatedMember() throws Exception {
        insertMember();

        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer " + tokenOf(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.email").value(MEMBER_ID + "@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("내 정보 테스트 회원"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://pic.example.com/me.png"))
                .andExpect(jsonPath("$.data.reportEmailEnabled").value(true));
    }

    @Test
    void respondsNotFoundWhenTheTokenMemberNoLongerExists() throws Exception {
        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer " + tokenOf(MISSING_MEMBER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_APP_002"));
    }

    @Test
    void respondsUnauthorizedWhenTogglingReportEmailWithoutAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportEmailEnabled\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void togglesReportEmailSettingAndReflectsItOnGet() throws Exception {
        insertMember();
        String token = tokenOf(MEMBER_ID);

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportEmailEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.reportEmailEnabled").value(false));

        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportEmailEnabled").value(false));
    }

    @Test
    void respondsUnauthorizedWhenRenamingWithoutAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"새 이름\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void renamesTheDisplayNameAndReflectsItOnGet() throws Exception {
        insertMember();
        String token = tokenOf(MEMBER_ID);

        // 앞뒤 공백은 다듬어 저장한다.
        mockMvc.perform(patch("/api/v1/members/me/display-name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  바뀐 이름  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.displayName").value("바뀐 이름"));

        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("바뀐 이름"));
    }

    @Test
    @Transactional
    void rejectsABlankDisplayName() throws Exception {
        insertMember();

        mockMvc.perform(patch("/api/v1/members/me/display-name")
                        .header("Authorization", "Bearer " + tokenOf(MEMBER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void respondsUnauthorizedWhenWithdrawingWithoutAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void withdrawsTheMemberAndStopsServingTheirStillValidToken() throws Exception {
        insertMember();
        // 탈퇴 뒤에도 만료 전까지 살아 있는 바로 그 토큰이다. 탈퇴가 즉시 먹히는지 이 토큰으로 확인한다.
        String token = tokenOf(MEMBER_ID);

        mockMvc.perform(delete("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));

        mockMvc.perform(get("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_APP_002"));
        mockMvc.perform(patch("/api/v1/members/me/display-name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"되살아나면 안 된다\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void withdrawalIsSoftAndReleasesTheGoogleSubjectSoTheAccountCanSignUpAgain() throws Exception {
        insertMember();

        mockMvc.perform(delete("/api/v1/members/me").header("Authorization", "Bearer " + tokenOf(MEMBER_ID)))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT google_subject, email, display_name, deleted_at FROM members WHERE id = ?", MEMBER_ID);
        // 행은 남고(소프트 삭제), 과거 리포트·참여자 목록이 쓰는 이름과 이메일도 그대로다.
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("email")).isEqualTo(MEMBER_ID + "@example.com");
        assertThat(row.get("display_name")).isEqualTo("내 정보 테스트 회원");
        // UNIQUE 인 google_subject 만 비켜 준다 — 안 그러면 같은 구글 계정이 다시 가입할 수 없다.
        assertThat(row.get("google_subject")).isEqualTo("withdrawn:" + MEMBER_ID);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM members WHERE google_subject = ?", Integer.class, "google-" + MEMBER_ID))
                .isZero();
    }

    @Test
    @Transactional
    void respondsNotFoundWhenWithdrawingTwice() throws Exception {
        insertMember();
        String token = tokenOf(MEMBER_ID);

        mockMvc.perform(delete("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_APP_002"));
    }
}
