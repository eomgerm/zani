package com.a105.zani.attention.application.recordpromptresponse;

/**
 * 프롬프트 응답 수집 결과.
 *
 * @param accepted 이번 요청으로 답이 기록되었는지
 * @param duplicate 같은 프롬프트에 이미 답이 있어 무시했는지. 재시도는 오류가 아니므로 성공으로 응답한다.
 */
public record RecordPromptResponseResult(boolean accepted, boolean duplicate) {

    /** 처음 받은 답이라 기록했다. */
    public static RecordPromptResponseResult recorded() {
        return new RecordPromptResponseResult(true, false);
    }

    /** 이미 답이 있어 첫 답을 그대로 두었다. */
    public static RecordPromptResponseResult alreadyRecorded() {
        return new RecordPromptResponseResult(false, true);
    }
}
