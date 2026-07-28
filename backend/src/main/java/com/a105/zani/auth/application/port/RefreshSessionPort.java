package com.a105.zani.auth.application.port;

public interface RefreshSessionPort {

    void create(RefreshSession session);

    boolean rotate(String currentTokenId, String subject, RefreshSession replacement);

    void revoke(String subject, String tokenId);
}
