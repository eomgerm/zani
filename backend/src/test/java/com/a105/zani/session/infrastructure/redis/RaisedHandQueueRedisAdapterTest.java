package com.a105.zani.session.infrastructure.redis;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.port.RaisedHandChange;
import com.a105.zani.session.application.port.RaisedHandQueuePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 손들기 큐의 동작 고정. 실제 Redis 로 확인한다.
 *
 * <p><b>스텁으로는 이 파일이 보는 것을 볼 수 없다.</b> 유스케이스 테스트의 스텁은 {@code ArrayList} 로 큐를 흉내 내며 우리가 기대한 값을 그대로 돌려준다. 정작 중요한 것은 진짜
 * {@code ZADD NX} 가 무엇을 돌려주는가인데, 그 값으로 이력 기록 여부가 갈린다 — 틀리면 {@code interaction_events} 가 중복으로 쌓이거나 아예 안 쌓인다.
 */
@SpringBootTest
class RaisedHandQueueRedisAdapterTest {

    private static final long SESSION_ID = 9_600_910L;

    /** 사전순으로는 뒤. 시각과 사전순이 어긋나야 정렬 근거를 구분할 수 있다. */
    private static final String EARLIER = "p-22";

    private static final String LATER = "p-11";

    @Autowired
    private RaisedHandQueuePort raisedHandQueuePort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key() {
        return "session:" + SESSION_ID + ":hands";
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
    void 처음_들면_바뀌었다고_알린다() {
        assertEquals(RaisedHandChange.CHANGED, raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L));
        assertEquals(List.of(EARLIER), raisedHandQueuePort.raisedInOrder(SESSION_ID));
    }

    /** 이 구분이 무너지면 재시도마다 이력이 한 줄씩 늘어 리포트의 손들기 횟수가 부풀려진다. */
    @Test
    void 이미_든_손을_다시_들면_안_바뀌었다고_알린다() {
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L);

        assertEquals(RaisedHandChange.UNCHANGED, raisedHandQueuePort.raise(SESSION_ID, EARLIER, 2_000L));
    }

    /** ZADD 는 기본적으로 score 를 덮어쓴다. NX 가 빠지면 재시도마다 목록이 재정렬돼 화면이 흔들린다. */
    @Test
    void 이미_든_손을_다시_들어도_자리가_그대로다() {
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L);
        raisedHandQueuePort.raise(SESSION_ID, LATER, 2_000L);

        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 3_000L);

        assertEquals(List.of(EARLIER, LATER), raisedHandQueuePort.raisedInOrder(SESSION_ID));
    }

    @Test
    void 서로_다른_시각이면_받은_순서대로_나온다() {
        raisedHandQueuePort.raise(SESSION_ID, LATER, 1_000L);
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 2_000L);

        assertEquals(List.of(LATER, EARLIER), raisedHandQueuePort.raisedInOrder(SESSION_ID));
    }

    /**
     * <b>알려진 한계를 고정한다.</b> score 가 같으면 Redis 는 member 사전순으로 정렬하므로, 같은 밀리초에 들어온 손들기는 실제 도착 순서를 잃는다.
     *
     * <p>화면이 이 목록을 집합으로만 쓰기로 해서 받아들인 동작이다. 순번을 노출하게 되면 세션별 {@code INCR} 순번을 score 로 쓰고 발급과 {@code ZADD} 를 Lua 로 묶어야 하며,
     * 그때 이 테스트가 먼저 깨져 알려 준다.
     */
    @Test
    void 같은_밀리초에_들어오면_도착_순서가_아니라_사전순이_된다() {
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L);
        raisedHandQueuePort.raise(SESSION_ID, LATER, 1_000L);

        // 먼저 들어온 것은 EARLIER("p-22") 지만 사전순이라 LATER("p-11") 가 앞선다.
        assertEquals(List.of(LATER, EARLIER), raisedHandQueuePort.raisedInOrder(SESSION_ID));
    }

    @Test
    void 손을_내리면_목록에서_빠진다() {
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L);
        raisedHandQueuePort.raise(SESSION_ID, LATER, 2_000L);

        assertEquals(RaisedHandChange.CHANGED, raisedHandQueuePort.lower(SESSION_ID, EARLIER));

        assertEquals(List.of(LATER), raisedHandQueuePort.raisedInOrder(SESSION_ID));
    }

    @Test
    void 들지_않은_손을_내리면_안_바뀌었다고_알린다() {
        assertEquals(RaisedHandChange.UNCHANGED, raisedHandQueuePort.lower(SESSION_ID, EARLIER));
    }

    @Test
    void 아무도_없으면_빈_목록이다() {
        assertTrue(raisedHandQueuePort.raisedInOrder(SESSION_ID).isEmpty());
    }

    /** 만료를 걸지 않으면 끝난 수업의 손들기가 Redis 에 영원히 남는다. */
    @Test
    void 손을_들면_만료_시간이_걸린다() {
        raisedHandQueuePort.raise(SESSION_ID, EARLIER, 1_000L);

        Long ttlSeconds = redisTemplate.getExpire(key());

        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0, "만료가 설정되지 않았습니다: " + ttlSeconds);
    }
}
