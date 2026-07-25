package com.a105.zani.recording.domain.model;

import java.util.Locale;
import java.util.regex.Pattern;

import com.a105.zani.recording.domain.exception.InvalidRecordingAliasException;

/**
 * 녹화 산출물에서 참가자를 가리키는 익명 별칭. manifest·파일 경로에는 이 값만 쓰이고, 실명·이메일·userId는 절대 넣지 않는다. 실제 참가자와의 연결은 DB(SessionParticipant)에서만
 * 유지한다. 강사는 {@code instructor}, 학생은 {@code student-001}부터 순번을 매긴다.
 */
public final class RecordingAlias {

    private static final String INSTRUCTOR_VALUE = "instructor";
    private static final int MAX_STUDENT_ORDER = 999;
    private static final Pattern STUDENT_PATTERN = Pattern.compile("student-(\\d{3})");

    private final String value;

    private RecordingAlias(String value) {
        this.value = value;
    }

    public static RecordingAlias instructor() {
        return new RecordingAlias(INSTRUCTOR_VALUE);
    }

    /** 1부터 시작하는 학생 순번으로 {@code student-001} 형식의 별칭을 만든다. */
    public static RecordingAlias student(int order) {
        if (order < 1 || order > MAX_STUDENT_ORDER) {
            throw new InvalidRecordingAliasException();
        }
        return new RecordingAlias(String.format(Locale.ROOT, "student-%03d", order));
    }

    /** 저장된 별칭 문자열(DB alias 컬럼, manifest 값)을 검증해 복원한다. 별칭 형식이 아니면(실명 등) 거부한다. */
    public static RecordingAlias of(String value) {
        if (INSTRUCTOR_VALUE.equals(value)) {
            return instructor();
        }
        if (value != null) {
            var matcher = STUDENT_PATTERN.matcher(value);
            if (matcher.matches()) {
                int order = Integer.parseInt(matcher.group(1));
                if (order >= 1 && order <= MAX_STUDENT_ORDER) {
                    return new RecordingAlias(value);
                }
            }
        }
        throw new InvalidRecordingAliasException();
    }

    public boolean isInstructor() {
        return INSTRUCTOR_VALUE.equals(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordingAlias alias && value.equals(alias.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
