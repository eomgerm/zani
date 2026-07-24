package com.a105.zani.auth.application.port;

public interface GoogleIdentityPort {

    GoogleIdentity verify(String idToken);
}
