package com.a105.zani.session.domain.repository;

import com.a105.zani.session.domain.model.SessionStatusChange;

public interface SessionStatusChangeRepository {

    void append(SessionStatusChange statusChange);
}
