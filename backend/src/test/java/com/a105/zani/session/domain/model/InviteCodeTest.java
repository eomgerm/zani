package com.a105.zani.session.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.a105.zani.session.domain.exception.InvalidInviteCodeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 사람이 옮겨 적는 값이라 형태가 일정하지 않다. 브라우저를 거치지 않는 호출도 있어 서버가 다시 맞춘다. */
class InviteCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"GPH7GQ5Q", "gph7gq5q", "GPH7-GQ5Q", "gph7-gq5q", " GPH7 GQ5Q ", "gPh7-gQ5q"})
    void canonicalizesEveryFormPeopleActuallyType(String raw) {
        assertEquals("GPH7GQ5Q", InviteCode.canonicalize(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GPH7GQ5", "GPH7GQ5QQ", "GPH7GQ5!", "", "   ", "--------"})
    void rejectsAnythingThatIsNotEightAlphanumericCharacters(String raw) {
        assertThrows(InvalidInviteCodeException.class, () -> InviteCode.canonicalize(raw));
    }

    @Test
    void rejectsNull() {
        assertThrows(InvalidInviteCodeException.class, () -> InviteCode.canonicalize(null));
    }
}
