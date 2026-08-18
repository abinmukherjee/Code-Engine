package com.distributedjudge.service;

import com.distributedjudge.dto.ProblemResponse;
import com.distributedjudge.dto.SubmissionStatusResponse;
import com.distributedjudge.model.Problem;
import com.distributedjudge.model.Submission;
import org.springframework.stereotype.Component;

@Component
public class SubmissionMapper {
    public ProblemResponse toProblemResponse(Problem problem) {
        return new ProblemResponse(
                problem.getId(),
                problem.getTitle(),
                problem.getStatement(),
                problem.getSampleInput(),
                problem.getSampleOutput(),
                problem.getDifficulty(),
                (int) problem.getTestCases().stream().filter(testCase -> testCase.isHidden()).count()
        );
    }

    public SubmissionStatusResponse toStatusResponse(Submission submission) {
        return new SubmissionStatusResponse(
                submission.getId(),
                submission.getUserId(),
                submission.getProblem().getId(),
                submission.getProblem().getTitle(),
                submission.getLanguage(),
                submission.getState(),
                submission.getVerdict(),
                submission.getRuntimeMs(),
                submission.getMemoryMb(),
                submission.getStdout(),
                submission.getStderr(),
                submission.getCreatedAt(),
                submission.getUpdatedAt()
        );
    }
}
