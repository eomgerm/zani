package com.a105.zani.session.application.getsessionlist;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetSessionListService implements GetSessionListUseCase {

    private final GetSessionListQueryPort queryPort;

    public GetSessionListService(GetSessionListQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionSummaryResult> getList(GetSessionListQuery query) {
        return queryPort.findByUserId(query.userId());
    }
}
