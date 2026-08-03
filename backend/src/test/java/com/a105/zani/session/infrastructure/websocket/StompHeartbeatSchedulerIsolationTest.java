package com.a105.zani.session.infrastructure.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;

import com.a105.zani.common.config.SchedulingConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 스케줄러 셋이 서로 풀을 빼앗지 않는지 지킨다.
 *
 * <p>이 세 가지가 한 풀에 섞이면 안 된다.
 *
 * <ul>
 *   <li>{@code @Scheduled} 작업 — 무음 패딩(100ms 주기)·녹화 outbox 릴레이(건당 최대 60초)·세션 만료
 *   <li>STOMP 브로커 내부 스케줄링 — {@code @EnableWebSocketMessageBroker} 가 만드는 {@code messageBrokerTaskScheduler}
 *   <li>STOMP 하트비트 — {@code StompConfig} 가 직접 만드는 전용 스케줄러
 * </ul>
 *
 * <p><b>기본값에 맡기면 실제로 섞인다.</b> 부트의 {@code TaskSchedulingAutoConfiguration} 은 {@code TaskScheduler} 타입 빈이 하나라도 있으면 물러나는데,
 * STOMP 를 켜는 순간 {@code messageBrokerTaskScheduler} 가 생겨 {@code @Scheduled} 가 그리로 흘러간다. 그러면
 * {@code spring.task.scheduling.pool.size} 가 무시되고, 코어가 적은 배포 장비에서는 설정보다 작은 풀을 쓰게 된다.
 *
 * <p>아무것도 실패하지 않고 성능만 무너지는 종류의 회귀라 컨텍스트가 뜨는지로는 잡히지 않는다.
 */
@SpringBootTest
class StompHeartbeatSchedulerIsolationTest {

    /**
     * {@code application.yaml} 의 {@code spring.task.scheduling.pool.size} 와 <b>같아야 하는</b> 값. 설정을 바꾸면 이 값도 함께 바꾼다.
     *
     * <p>같은 숫자를 두 곳에 손으로 적는 구조라 실제로 한 번 어긋났다 — 알림 이메일 릴레이가 붙어 설정이 7 로 오르는 동안 이 상수가 6 에 남아 있었다(116). 스케줄 작업을 늘릴 때 두 곳을
     * 같이 보라.
     *
     * <p>{@code SchedulingPoolSizeTest} 가 같은 설정을 스케줄 작업 수 기준으로 따로 지킨다. 그쪽은 YAML 텍스트만 읽으므로 <b>설정이 실제로 쓰이는지</b>는 이 테스트만
     * 안다 — {@code @Scheduled} 가 브로커 풀로 새던 동안에도 그 테스트는 초록이었다.
     */
    private static final int SCHEDULED_POOL_SIZE = 7;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SchedulingConfig schedulingConfig;

    @Test
    void 스케줄_작업은_설정한_크기의_풀을_쓴다() {
        assertEquals(
                SCHEDULED_POOL_SIZE,
                schedulingConfig.scheduler().getScheduledThreadPoolExecutor().getCorePoolSize());
    }

    /** 브로커 스케줄러로 흘러가면 풀 크기가 {@code availableProcessors()} 가 되어 위 설정이 무시된다. */
    @Test
    void 스케줄_작업은_브로커_스케줄러를_쓰지_않는다() {
        TaskScheduler broker = context.getBean("messageBrokerTaskScheduler", TaskScheduler.class);

        assertNotSame(broker, schedulingConfig.scheduler());
    }

    /** 하트비트 스케줄러는 빈이 아니다. 빈이 되면 {@code @Scheduled} 후보가 되어 같은 문제가 되돌아온다. */
    @Test
    void TaskScheduler_빈은_브로커의_것_하나뿐이다() {
        assertEquals(
                1,
                context.getBeanNamesForType(TaskScheduler.class).length,
                "TaskScheduler 빈이 늘었습니다. @Scheduled 가 어느 풀을 쓰는지 다시 확인하세요.");
    }
}
