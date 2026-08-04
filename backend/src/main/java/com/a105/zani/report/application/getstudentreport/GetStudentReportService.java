package com.a105.zani.report.application.getstudentreport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.exception.NotSessionStudentReportException;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

@Service
@RequiredArgsConstructor
public class GetStudentReportService implements GetStudentReportUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final StudentReportQueryPort queryPort;

    @Override
    @Transactional(readOnly = true)
    public GetStudentReportResult get(GetStudentReportQuery query) {
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(query.sessionId(), query.memberId()));
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentReportException();
        }

        StudentReportView view = queryPort
                .findBySessionIdAndParticipantId(query.sessionId(), participant.participantId())
                .orElseThrow(ReportNotReadyException::new);

        return new GetStudentReportResult(
                new GetStudentReportResult.Activity(view.publicChatCount(), view.confusedCount(), view.missedCount()),
                view.participationSummary(),
                view.recommendations().stream()
                        .map(recommendation -> new GetStudentReportResult.Recommendation(
                                recommendation.recommendationType(),
                                recommendation.title(),
                                recommendation.description(),
                                recommendation.startSeconds(),
                                recommendation.endSeconds(),
                                recommendation.priority()))
                        .toList());
    }
}
