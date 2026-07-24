package com.a105.zani.session.infrastructure.member;

import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.MemberDisplayNamePort;

/** members 테이블에서 표시 이름만 읽는다. member 도메인 내부 타입을 import하지 않고 공유 테이블을 SQL로 조회해 도메인 간 결합을 피한다. */
@Component
public class MemberDisplayNameJdbcAdapter implements MemberDisplayNamePort {

    private final JdbcTemplate jdbcTemplate;

    public MemberDisplayNameJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findDisplayName(Long memberId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT display_name FROM members WHERE id = ?", String.class, memberId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }
}
