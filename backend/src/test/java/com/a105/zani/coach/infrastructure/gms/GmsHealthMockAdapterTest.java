package com.a105.zani.coach.infrastructure.gms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GmsHealthMockAdapterTest {

    @Test
    void mockAlwaysReportsReachableWithoutRealCall() {
        assertTrue(new GmsHealthMockAdapter().isGmsReachable());
    }
}
