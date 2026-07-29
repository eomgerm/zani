package com.a105.zani.session.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.a105.zani.session.domain.exception.InvalidInviteCodeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InviteCodeTest {

    /** UI 는 하이픈을 넣어 보여 주고 사용자는 소문자로 옮겨 적는다. 어느 쪽이든 같은 저장형으로 모여야 한다. */
    @ParameterizedTest
    @ValueSource(strings = {"A7KM2PQR", "A7KM-2PQR", "a7km2pqr", "a7km-2pqr", " A7KM 2PQR ", "A7km-2PqR"})
    void normalisesEveryFormTheUserMightType(String raw) {
        assertEquals("A7KM2PQR", InviteCode.normalize(raw).value());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "A7KM2PQ", "A7KM2PQRS", "A7KM_2PQR", "A7KM 2PQ", "한글코드입니다", "A7KM-2PQ"})
    void rejectsAnythingThatIsNotEightAlphanumericCharacters(String raw) {
        assertThrows(InvalidInviteCodeException.class, () -> InviteCode.normalize(raw));
    }

    @Test
    void rendersTheDisplayFormInTwoGroups() {
        assertEquals("A7KM-2PQR", InviteCode.normalize("A7KM2PQR").display());
    }

    /** 검증 알파벳이 생성 알파벳보다 넓은 것은 의도된 것이다. 이 규칙이 생기기 전에 발급된 코드에는 O·0·1 이 이미 섞여 있어, 좁혀 버리면 진행 중인 수업에 학생이 들어가지 못한다. */
    @Test
    void stillAcceptsLegacyCodesContainingAmbiguousCharacters() {
        assertEquals("O0I1LABC", InviteCode.normalize("o0i1labc").value());
    }

    @Test
    void generationAlphabetLeavesOutCharactersThatAreEasyToMisread() {
        for (char ambiguous : new char[] {'O', 'I', 'L', '0', '1'}) {
            assertEquals(
                    -1,
                    InviteCode.GENERATION_ALPHABET.indexOf(ambiguous),
                    () -> "generation alphabet must not contain " + ambiguous);
        }
    }

    @Test
    void twoCodesWithTheSameCanonicalValueAreEqual() {
        assertEquals(InviteCode.normalize("a7km-2pqr"), InviteCode.normalize("A7KM2PQR"));
    }
}
