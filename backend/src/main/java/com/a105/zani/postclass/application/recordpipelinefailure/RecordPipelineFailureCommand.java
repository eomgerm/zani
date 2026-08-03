package com.a105.zani.postclass.application.recordpipelinefailure;

/**
 * 사후 처리 단계가 실패했다는 보고.
 *
 * @param sessionId 실패한 작업의 세션 ID
 * @param reason 실패 사유. 기록으로 남아 운영에서 원인을 가르는 근거가 된다
 * @param retryable 다시 시도하면 결과가 달라질 수 있는 실패인지. 무엇이 재시도할 만한지는 단계마다 다르므로 단계를 수행한 쪽이 판단한다 — GMS 호출 한도 초과는 기다리면 풀리지만 구조화 스키마
 *     위반은 몇 번을 보내도 같다
 */
public record RecordPipelineFailureCommand(Long sessionId, String reason, boolean retryable) {}
