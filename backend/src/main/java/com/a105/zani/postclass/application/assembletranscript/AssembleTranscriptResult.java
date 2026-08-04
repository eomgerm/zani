package com.a105.zani.postclass.application.assembletranscript;

/**
 * 조립 결과 요약(S15P11A105-247).
 *
 * <p>문서 자체를 돌려주지 않는다. 저장까지가 이 use case 의 책임이고, 호출자는 로그와 다음 단계 판정에 쓸 수치만 필요하다. 문서를 돌려주면 호출자가 그것을 또 어디에 쓸지 열리는데, 정본은 DB 한
 * 곳이어야 한다.
 *
 * @param segmentCount 저장한 세그먼트 수
 * @param speakerCount 발화가 하나라도 있는 화자 수. 0 이면 마이크가 한 번도 열리지 않은 수업이다
 * @param lastEndOffsetMs 마지막 발화의 종료 시각. 수업 길이와 비교해 시간축이 밀렸는지 눈으로 확인할 때 쓴다
 */
public record AssembleTranscriptResult(int segmentCount, int speakerCount, long lastEndOffsetMs) {}
