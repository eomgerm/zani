package com.a105.zani.session.application.port;

import java.time.Duration;

public interface SessionActivationLockPort {

    boolean tryAcquire(long instructorId, Duration ttl);

    void release(long instructorId);
}
