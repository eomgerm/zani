package com.a105.zani.common.persistence;

import io.hypersistence.tsid.TSID;

public final class TsidGenerator {

    private TsidGenerator() {}

    public static long generate() {
        return TSID.Factory.getTsid().toLong();
    }
}
