package com.a105.zani.auth.application.port;

public interface RefreshSessionPort {

    boolean rotate(String currentTokenId, String subject, RefreshSession replacement);
}
