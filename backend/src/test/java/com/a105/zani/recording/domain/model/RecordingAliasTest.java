package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.InvalidRecordingAliasException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingAliasTest {

    @Test
    void 강사_별칭은_instructor_고정이다() {
        assertEquals("instructor", RecordingAlias.instructor().value());
        assertTrue(RecordingAlias.instructor().isInstructor());
    }

    @Test
    void 학생_별칭은_3자리_순번으로_익명화된다() {
        assertEquals("student-001", RecordingAlias.student(1).value());
        assertEquals("student-012", RecordingAlias.student(12).value());
        assertEquals("student-999", RecordingAlias.student(999).value());
        assertFalse(RecordingAlias.student(1).isInstructor());
    }

    @Test
    void 순번이_범위를_벗어나면_예외() {
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.student(0));
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.student(1000));
    }

    @Test
    void 저장된_별칭_문자열을_복원할_수_있다() {
        assertEquals("instructor", RecordingAlias.of("instructor").value());
        assertEquals("student-012", RecordingAlias.of("student-012").value());
    }

    @Test
    void 별칭_형식이_아니면_복원을_거부한다() {
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.of("김태정"));
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.of("student-1"));
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.of("student-000"));
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.of("STUDENT-001"));
        assertThrows(InvalidRecordingAliasException.class, () -> RecordingAlias.of(null));
    }

    @Test
    void 같은_순번은_동등하다() {
        assertEquals(RecordingAlias.student(3), RecordingAlias.student(3));
        assertEquals(
                RecordingAlias.student(3).hashCode(), RecordingAlias.student(3).hashCode());
    }
}
