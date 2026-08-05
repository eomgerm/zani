package com.a105.zani.postclass.application.port;

import tools.jackson.databind.ObjectMapper;

/**
 * GMS 사용자 메시지가 <b>본문에 실리는 형태</b>로 차지할 수 있는 크기를 잰다(S15P11A105-304).
 *
 * <p>학생별 분석(S15P11A105-249)과 강사 분석(S15P11A105-250)이 같은 판단을 각자 들고 있었고 그중 하나가 이스케이프 전 크기로 재고 있었다. 재는 자를 한 곳에 두어 다시 갈라지지
 * 않게 한다. 249 가 전용 클래스를 두지 않겠다고 적은 이유는 소비자가 하나뿐이었기 때문이고, 이제 둘이다.
 *
 * <p>넘쳤을 때 <b>무엇을 줄일지는 각 서비스가 정한다</b> — 학생은 관측을 집계로 접고 강사는 채팅을 구간마다 앞에서 열 개만 남긴다. 그 선택은 업무 판단이라 이 클래스가 가져오지 않는다.
 */
public final class GmsContentSizeGuard {

    /**
     * 사용자 메시지가 본문에 실릴 때 차지할 수 있는 바이트 상한. 92 KiB.
     *
     * <p>게이트웨이 실측 상한이 102,400 B 다(GMS 가이드 §4.1 — 바이트 단위 이분 탐색). 초과하면 게이트웨이가 본문을 잘라 전달하고 업스트림이 "model not found" 를 돌려주므로
     * <b>크기 문제라는 사실이 오류 메시지에 드러나지 않는다.</b>
     *
     * <p><b>이스케이프된 크기로 잰다.</b> 사용자 메시지는 {@code messages[1].content} 의 <i>문자열 값</i>으로 들어가므로 본문에 실릴 때 따옴표마다 한 바이트가 늘고
     * 줄바꿈이 두 글자가 된다. 그 증가분은 payload 모양에 비례한다 — 짧은 레코드가 많을수록 바이트당 따옴표가 많아 커진다. 고정 예산으로는 덮을 수 없어 측정 안으로 넣었다
     * ({@link #fits}). 예전에는 이스케이프 전 크기를 재서 실제 전송량을 약 10% 작게 봤고, 82% 지점 실측이라 그 차이가 가려져 있었다.
     *
     * <p>남는 10 KiB 는 <b>고정분</b>만의 몫이다 — chat completions 봉투(model·temperature·max_completion_tokens)와 strict 응답 스키마,
     * 시스템 프롬프트. 셋 다 payload 크기와 무관하고 실측 합이 약 5.4 KB 라 프롬프트가 자랄 여유를 두 배 가까이 둔다.
     *
     * <p>설정으로 열지 않는다. 환경별로 달라질 이유가 없고, 잘못 올리면 조용한 절단을 부른다.
     */
    public static final int MAX_ESCAPED_CONTENT_BYTES = 94_208;

    private GmsContentSizeGuard() {}

    /**
     * 보낼 데이터가 상한 안에 드는지 본다.
     *
     * <p>한 번 직렬화해 어댑터가 {@code content} 에 넣을 문자열을 만들고, 그 문자열을 다시 직렬화해 본문에 실릴 형태(이스케이프 + 감싸는 따옴표)로 잰다. <b>재는 값과 보내는 값이
     * 같아야 한다</b> — 어댑터가 payload 를 문자열로 만들어 사용자 메시지에 싣는 것과 같은 순서다.
     *
     * @param payload 어댑터가 사용자 메시지 본문으로 직렬화할 값
     */
    public static boolean fits(ObjectMapper mapper, Object payload) {
        String content = mapper.writeValueAsString(payload);
        return mapper.writeValueAsBytes(content).length <= MAX_ESCAPED_CONTENT_BYTES;
    }
}
