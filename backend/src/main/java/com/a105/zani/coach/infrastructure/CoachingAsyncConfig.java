package com.a105.zani.coach.infrastructure;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 코칭 헬스체크 리스너를 비동기로 실행하기 위한 설정. 수업 생성 응답이 GMS 헬스체크(네트워크 호출)로 지연되지 않게 한다.
 *
 * <p>기본 실행기(SimpleAsyncTaskExecutor)는 요청마다 새 스레드를 만들어 상한이 없다. 수업 생성이 몰릴 때 스레드가 무제한으로 늘어나지 않도록 상한이 있는 전용 풀을 둔다. 헬스체크는
 * 세션당 1회, 수 초 이내의 짧은 작업이라 작은 풀로 충분하다.
 *
 * <p>큐가 가득 차면 호출 스레드에서 실행하지 않고 작업을 버린다({@link ThreadPoolExecutor.DiscardPolicy}). 코칭 가용 여부는 부가 정보이며, 호출 스레드(수업 생성)를
 * 네트워크 호출로 막지 않는 것이 우선이다. 값이 없으면 소비자가 비활성으로 취급한다.
 */
@Configuration
@EnableAsync
public class CoachingAsyncConfig {

    public static final String COACHING_TASK_EXECUTOR = "coachingTaskExecutor";

    @Bean(COACHING_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor coachingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("coaching-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // initialize() 는 호출하지 않는다. ThreadPoolTaskExecutor 는 InitializingBean 이라
        // Spring 이 afterPropertiesSet() 에서 초기화하며, 수동 호출은 이중 초기화가 된다.
        return executor;
    }
}
