package com.a105.zani.session.application.get;

import java.util.List;

public interface GetSessionListQueryPort {

    List<SessionSummaryResult> findByUserId(long userId);
}
