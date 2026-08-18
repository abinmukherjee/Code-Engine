package com.distributedjudge.service;

import com.distributedjudge.config.JudgeProperties;
import com.distributedjudge.config.KafkaTopics;
import com.distributedjudge.dto.MetricsSnapshot;
import com.distributedjudge.dto.ProblemResponse;
import com.distributedjudge.dto.SubmissionAcceptedResponse;
import com.distributedjudge.dto.SubmissionMessage;
import com.distributedjudge.dto.SubmissionRequest;
import com.distributedjudge.dto.SubmissionStatusResponse;
import com.distributedjudge.model.Problem;
import com.distributedjudge.model.Submission;
import com.distributedjudge.model.SubmissionState;
import com.distributedjudge.model.Verdict;
import com.distributedjudge.repository.ProblemRepository;
import com.distributedjudge.repository.SubmissionRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!worker")
public class SubmissionService {
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final KafkaTemplate<String, SubmissionMessage> kafkaTemplate;
    private final RateLimiterService rateLimiterService;
    private final SubmissionMapper mapper;
    private final JudgeProperties properties;

    public SubmissionService(
            ProblemRepository problemRepository,
            SubmissionRepository submissionRepository,
            @Qualifier("submissionKafkaTemplate") KafkaTemplate<String, SubmissionMessage> kafkaTemplate,
            RateLimiterService rateLimiterService,
            SubmissionMapper mapper,
            JudgeProperties properties
    ) {
        this.problemRepository = problemRepository;
        this.submissionRepository = submissionRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.rateLimiterService = rateLimiterService;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public SubmissionAcceptedResponse submit(SubmissionRequest request, String authenticatedEmail) {
        if (request.sourceCode().length() > properties.getMaxCodeLength()) {
            throw new IllegalArgumentException("Source code exceeds " + properties.getMaxCodeLength() + " characters");
        }

        RateLimitResult limit = rateLimiterService.checkLimit(authenticatedEmail, 1);
        if (!limit.allowed()) {
            throw new RateLimitExceededException(limit.retryAfterSeconds());
        }

        Problem problem = problemRepository.findById(request.problemId())
                .orElseThrow(() -> new NotFoundException("Problem " + request.problemId() + " was not found"));

        Submission submission = new Submission();
        submission.setUserId(authenticatedEmail);
        submission.setProblem(problem);
        submission.setLanguage(request.language());
        submission.setSourceCode(request.sourceCode());
        submission.setState(SubmissionState.PENDING);
        submission.setVerdict(Verdict.PENDING);
        submission.setCreatedAt(Instant.now());
        submission.setUpdatedAt(Instant.now());
        Submission saved = submissionRepository.save(submission);

        try {
            kafkaTemplate.send(KafkaTopics.SUBMISSIONS, saved.getId().toString(), new SubmissionMessage(saved.getId()))
                    .get(3, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            saved.setState(SubmissionState.FAILED);
            saved.setVerdict(Verdict.RUNTIME_ERROR);
            saved.setStderr("Failed to enqueue submission for execution; retry later");
            saved.setUpdatedAt(Instant.now());
            submissionRepository.save(saved);
            throw new IllegalStateException("Submission queue is unavailable");
        }

        return new SubmissionAcceptedResponse(
                saved.getId(),
                "/api/submissions/" + saved.getId(),
                KafkaTopics.SUBMISSIONS,
                saved.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public SubmissionStatusResponse getStatus(Long id, String authenticatedEmail) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Submission " + id + " was not found"));
        if (!submission.getUserId().equals(authenticatedEmail)) {
            throw new ForbiddenException("You do not have access to submission " + id);
        }
        return mapper.toStatusResponse(submission);
    }

    @Transactional(readOnly = true)
    public List<SubmissionStatusResponse> recentSubmissions() {
        return submissionRepository.findTop10ByOrderByCreatedAtDesc().stream()
                .map(mapper::toStatusResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProblemResponse> listProblems() {
        return problemRepository.findAll().stream()
                .map(problem -> problemRepository.findWithTestCasesById(problem.getId()).orElse(problem))
                .map(mapper::toProblemResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProblemResponse getProblem(Long id) {
        Problem problem = problemRepository.findWithTestCasesById(id)
                .orElseThrow(() -> new NotFoundException("Problem " + id + " was not found"));
        return mapper.toProblemResponse(problem);
    }

    @Transactional(readOnly = true)
    public MetricsSnapshot metrics() {
        return new MetricsSnapshot(
                submissionRepository.countByState(SubmissionState.PENDING),
                submissionRepository.countByState(SubmissionState.RUNNING),
                submissionRepository.countByState(SubmissionState.COMPLETED),
                submissionRepository.countByVerdict(Verdict.ACCEPTED),
                submissionRepository.countByVerdict(Verdict.WRONG_ANSWER),
                submissionRepository.countByVerdict(Verdict.TIME_LIMIT_EXCEEDED),
                submissionRepository.countByVerdict(Verdict.RUNTIME_ERROR),
                rateLimiterService.rejections()
        );
    }
}
