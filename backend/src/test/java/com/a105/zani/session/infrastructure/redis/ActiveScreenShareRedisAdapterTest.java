package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.port.ActiveScreenSharePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 활성 화면 공유 슬롯의 동작 고정. 실제 Redis 로 확인한다.
 *
 * <p><b>스텁으로는 이 파일이 보는 것을 볼 수 없다.</b> {@code ScreenShareServiceTest} 는 {@code HashMap} 으로 슬롯을 흉내 내므로 Lua 스크립트도 TTL 도 한
 * 번도 실행되지 않는다. 정작 "한 번에 하나"(FRD §10.2)를 지탱하는 것이 그 둘이다.
 *
 * <p><b>손으로는 확인할 수 없다.</b> 사람이 두 브라우저에서 공유를 눌러도 항상 한쪽이 먼저 도착해 경합이 생기지 않고, 크래시한 공유자의 슬롯이 풀리는지는 TTL 이 지나기를 기다려야 한다.
 */
@SpringBootTest
class ActiveScreenShareRedisAdapterTest {

    private static final long SESSION_ID = 9_700_910L;

    private static final long SHARER = 9_700_911L;
    private static final long OTHER = 9_700_912L;

    /** 만료를 눈으로 보려면 TTL 이 짧아야 한다. 초 단위로 저장되므로 1초 미만은 쓸 수 없다. */
    private static final Duration BRIEF = Duration.ofSeconds(1);

    private static final Duration LONG = Duration.ofSeconds(30);

    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private ActiveScreenSharePort activeScreenSharePort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key() {
        return "session:" + SESSION_ID + ":screenshare";
    }

    @BeforeEach
    void setUp() {
        redisTemplate.delete(key());
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(key());
    }

    @Test
    void 빈_슬롯은_획득된다() {
        assertTrue(activeScreenSharePort.claim(SESSION_ID, SHARER, LONG));

        assertEquals(SHARER, activeScreenSharePort.currentSharer(SESSION_ID).orElseThrow());
    }

    @Test
    void 다른_참가자가_공유_중이면_거부하고_슬롯을_건드리지_않는다() {
        activeScreenSharePort.claim(SESSION_ID, SHARER, LONG);

        assertFalse(activeScreenSharePort.claim(SESSION_ID, OTHER, LONG));
        // 거부하면서 남의 슬롯을 덮어쓰면 두 사람이 동시에 공유 중이라고 믿게 된다.
        assertEquals(SHARER, activeScreenSharePort.currentSharer(SESSION_ID).orElseThrow());
    }

    /** 공유자 FE 는 공유 중 주기적으로 다시 부른다. 이 호출이 TTL 을 늘리지 못하면 공유 도중에 슬롯을 빼앗긴다. */
    @Test
    void 소유자가_다시_부르면_TTL이_갱신된다() {
        activeScreenSharePort.claim(SESSION_ID, SHARER, BRIEF);
        Long beforeRefresh = redisTemplate.getExpire(key(), TimeUnit.SECONDS);

        assertTrue(activeScreenSharePort.claim(SESSION_ID, SHARER, LONG));

        Long afterRefresh = redisTemplate.getExpire(key(), TimeUnit.SECONDS);
        assertNotNull(beforeRefresh);
        assertNotNull(afterRefresh);
        assertTrue(afterRefresh > beforeRefresh, "다시 불렀는데 만료가 늘지 않았다: " + beforeRefresh + " → " + afterRefresh);
    }

    /**
     * 크래시·강제 종료·긴 네트워크 단절로 갱신이 끊긴 경우다. 여기가 깨지면 슬롯이 영영 잠겨, 나간 사람 때문에 남은 수업 내내 아무도 공유하지 못한다.
     *
     * <p>티켓 원문의 "재연결 정리"가 이 동작이다. 세션 종료·퇴장은 명시적 release 로 즉시 비우므로 TTL 을 기다리지 않는다.
     */
    @Test
    void TTL이_지나면_슬롯이_비고_다음_사람이_가져간다() throws Exception {
        activeScreenSharePort.claim(SESSION_ID, SHARER, BRIEF);

        Thread.sleep(BRIEF.toMillis() + 300);

        assertTrue(activeScreenSharePort.currentSharer(SESSION_ID).isEmpty());
        assertTrue(activeScreenSharePort.claim(SESSION_ID, OTHER, LONG));
    }

    @Test
    void 소유자가_해제하면_슬롯이_빈다() {
        activeScreenSharePort.claim(SESSION_ID, SHARER, LONG);

        activeScreenSharePort.release(SESSION_ID, SHARER);

        assertTrue(activeScreenSharePort.currentSharer(SESSION_ID).isEmpty());
    }

    /** 앞사람이 뒤늦게 보낸 정리 요청으로 지금 공유 중인 사람이 끊기면 안 된다. */
    @Test
    void 소유자가_아니면_해제해도_슬롯이_남는다() {
        activeScreenSharePort.claim(SESSION_ID, SHARER, LONG);

        activeScreenSharePort.release(SESSION_ID, OTHER);

        assertEquals(SHARER, activeScreenSharePort.currentSharer(SESSION_ID).orElseThrow());
    }

    /**
     * "한 번에 하나"가 실제로 지켜지는지 보는 유일한 자리다.
     *
     * <p>GET 후 SET 을 애플리케이션에서 둘로 나누면 여러 참가자가 동시에 "비어 있음"을 읽고 모두 심는다. Lua 로 합쳐 두었기에 막히는 것이므로, 스크립트가 바뀌면 여기서 드러나야 한다.
     */
    @Test
    void 동시에_요청해도_한_명만_획득한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger acquired = new AtomicInteger();

        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                long participantId = SHARER + i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (activeScreenSharePort.claim(SESSION_ID, participantId, LONG)) {
                            acquired.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "모든 요청이 준비되지 못했다");
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "모든 요청이 끝나지 못했다");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, acquired.get(), "동시에 요청했는데 슬롯을 얻은 사람이 하나가 아니다");
        assertTrue(activeScreenSharePort.currentSharer(SESSION_ID).isPresent());
    }
}
