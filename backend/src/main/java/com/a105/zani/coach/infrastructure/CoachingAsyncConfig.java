package com.a105.zani.coach.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 코칭 헬스체크 리스너를 비동기로 실행하기 위한 설정. 수업 생성 응답이 GMS 헬스체크(네트워크 호출)로 지연되지 않게 한다. */
@Configuration
@EnableAsync
public class CoachingAsyncConfig {}
