package com.a105.zani.report.application.getstudentreport;

import java.util.Optional;

public interface StudentReportQueryPort {

    Optional<StudentReportView> findBySessionIdAndParticipantId(long sessionId, long participantId);
}
