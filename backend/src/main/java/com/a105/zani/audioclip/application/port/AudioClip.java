package com.a105.zani.audioclip.application.port;

import java.time.Duration;

/**
 * 전사에 바로 넘길 수 있는 클립. 전사 작업(티켓 203)과의 계약이다.
 *
 * <p>파일을 만들지 않고 메모리에서 조립해 돌려준다. "전사 후 즉시 폐기"가 구조적으로 보장되고 고아 파일을 청소할 일도 없다.
 *
 * @param audio 인코딩이 끝난 16kHz 모노 오디오. 형식은 {@code contentType} 이 알려준다
 * @param contentType {@code audio} 의 MIME 타입. 형식을 아는 쪽은 인코더뿐이라 값을 데이터와 함께 실어 보낸다. 상위가 하드코딩하면 인코더를 바꿀 때 조용히 어긋난다
 * @param fromEpochMs 이 구간의 시작 벽시계 시각(무음 패딩으로 정렬돼 있다)
 * @param toEpochMs 이 구간의 끝 벽시계 시각
 * @param actual 실제로 확보된 길이. 요청한 window 보다 짧을 수 있다(수업 시작 직후 등). 전사·팁·쿨타임을 건너뛸지 판단하는 근거다
 */
public record AudioClip(byte[] audio, String contentType, long fromEpochMs, long toEpochMs, Duration actual) {}
