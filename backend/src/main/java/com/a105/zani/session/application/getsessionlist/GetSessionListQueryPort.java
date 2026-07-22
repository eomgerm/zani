package com.a105.zani.session.application.getsessionlist;

import java.util.List;

public interface GetSessionListQueryPort {

    List<SessionSummaryResult> findByUserId(long userId);
}
