package com.a105.zani.auth.application.googlelogin;

public interface GoogleLoginUseCase {

    GoogleLoginResult login(GoogleLoginCommand command);
}
