package com.a105.zani.postclass.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사후 분석 디스패치 설정(S15P11A105-304).
 *
 * <p>전사({@code postclass.transcription})와 접두사를 나눈다. 합치면 전사를 끄는 순간 분석까지 꺼지고, 폴링 주기도 따로 조절할 수 없다.
 *
 * @param pollDelay 분석 대기 작업을 훑는 주기
 * @param dispatchBatchSize 한 주기에 훑을 작업 수. 오케스트레이션 실행기가 크기 1·큐 0 이라 실제로 시작되는 것은 하나뿐이고, 나머지는 제출이 거부돼 다음 주기로 넘어간다. 1 보다 크게
 *     두는 이유는 앞선 세션이 이미 다른 실행에 넘어간 경우(선점 실패) 같은 주기에 다음 후보를 시도할 수 있게 하려는 것이다
 * @param leaseDuration 분석 실행권의 유효 기간. 이 시간이 지나면 선점한 실행이 죽은 것으로 보고 다른 실행이 이어받는다. <b>한 세션의 세 분석이 끝나기에 넉넉해야 한다</b> — 학생
 *     30명이면 GMS 호출이 30회이고 회당 최악 60초라 30분, 여기에 공통·강사 분석을 더한 값이 기본이다. 짧으면 살아 있는 실행의 세션을 다른 실행이 가져가 같은 GMS 호출이 두 번 나가고, 길면
 *     죽은 실행의 세션이 그만큼 묶인다. 전체 마감은 8시간이라 한 번 잃어도 만회할 여지가 있다
 * @param enabled 배경 디스패치를 켤지. 테스트가 배경 폴링 없이 유스케이스를 직접 부를 수 있게 열어 둔다
 */
@ConfigurationProperties(prefix = "postclass.analysis")
public record PostClassAnalysisProperties(
        @DefaultValue("PT10S") Duration pollDelay,
        @DefaultValue("5") int dispatchBatchSize,
        @DefaultValue("PT60M") Duration leaseDuration,
        @DefaultValue("true") boolean enabled) {

    public PostClassAnalysisProperties {
        if (dispatchBatchSize < 1) {
            throw new IllegalArgumentException("디스패치 배치 크기는 1 이상이어야 합니다: " + dispatchBatchSize);
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("임대 기간은 양수여야 합니다: " + leaseDuration);
        }
    }
}
