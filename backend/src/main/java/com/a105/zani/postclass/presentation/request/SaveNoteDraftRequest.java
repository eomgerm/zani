package com.a105.zani.postclass.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강사 사후 메모 초안. 수업 하나에 본문 하나이며 구간으로 나누지 않는다.
 *
 * <p>길이 제한을 Bean Validation 으로 걸지 않는 이유: 도메인은 앞뒤 공백을 다듬은 뒤 길이를 본다. 요청 쪽에서 원본 길이로 먼저 자르면, 공백을 붙여 5000자를 넘겼지만 다듬으면 5000자
 * 이하인 입력이 잘못 거절된다. 검사 지점을 한 곳(도메인)으로 둔다.
 */
@Schema(description = "강사 사후 메모 초안. 저장될 때마다 30분 비활성 타이머가 초기화된다.")
public record SaveNoteDraftRequest(
        @Schema(description = """
                                메모 본문. 개념명·강조 이유·학생이 다시 볼 포인트를 자유 형식으로 적는다.
                                비워 두거나 생략할 수 있다 — 쓰던 내용을 지운 상태도 그대로 저장된다.""", example = "재귀 종료 조건에서 학생 절반이 헷갈려했다. 복습 추천에 반영되면 좋겠다.", maxLength = 5_000)
        String content) {}
