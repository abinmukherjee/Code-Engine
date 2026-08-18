package com.distributedjudge.service;

import com.distributedjudge.config.JudgeProperties;
import com.distributedjudge.config.KafkaTopics;
import com.distributedjudge.dto.SubmissionMessage;
import com.distributedjudge.model.Problem;
import com.distributedjudge.model.Submission;
import com.distributedjudge.model.SubmissionState;
import com.distributedjudge.repository.ProblemRepository;
import com.distributedjudge.repository.SubmissionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Retries are handled by the Kafka consumer's error handler (see KafkaConsumerConfig):
 * process() lets failures propagate so a failed submission is redelivered a few times
 * before landing on the dead-letter topic, where onDeadLettered marks it FAILED for good.
 */
@Component
@Profile("!gateway")
public class ExecutionWorker {
    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final SandboxExecutor sandboxExecutor;
    private final JudgeProperties properties;
    private final Timer latencyTimer;

    public ExecutionWorker(
            SubmissionRepository submissionRepository,
            ProblemRepository problemRepository,
            SandboxExecutor sandboxExecutor,
            JudgeProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.submissionRepository = submissionRepository;
        this.problemRepository = problemRepository;
        this.sandboxExecutor = sandboxExecutor;
        this.properties = properties;
        this.latencyTimer = Timer.builder("judge_worker_latency_ms")
                .description("End-to-end execution latency per submission")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopics.SUBMISSIONS,
            groupId = KafkaTopics.CONSUMER_GROUP,
            containerFactory = "submissionKafkaListenerContainerFactory"
    )
    public void onMessage(SubmissionMessage message, Acknowledgment acknowledgment) {
        process(message.submissionId());
        acknowledgment.acknowledge();
    }

    @KafkaListener(
            topics = KafkaTopics.SUBMISSIONS_DLQ,
            groupId = KafkaTopics.CONSUMER_GROUP + "-dlq",
            containerFactory = "submissionKafkaListenerContainerFactory"
    )
    public void onDeadLettered(
            SubmissionMessage message,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String reason,
            Acknowledgment acknowledgment
    ) {
        submissionRepository.findById(message.submissionId()).ifPresent(submission -> {
            submission.setState(SubmissionState.FAILED);
            submission.setStderr("Execution failed after retries: " + (reason != null ? reason : "unknown error"));
            submission.setUpdatedAt(Instant.now());
            submissionRepository.save(submission);
        });
        acknowledgment.acknowledge();
    }

    void process(Long submissionId) {
        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            return;
        }

        submission.setState(SubmissionState.RUNNING);
        submission.setUpdatedAt(Instant.now());
        submissionRepository.saveAndFlush(submission);

        Long problemId = submission.getProblem().getId();
        Problem problem = problemRepository.findWithTestCasesById(problemId)
                .orElseThrow(() -> new NotFoundException("Problem " + problemId + " was not found"));

        SandboxResult result = latencyTimer.record(() ->
                sandboxExecutor.execute(submission.getLanguage(), submission.getSourceCode(), problem.getTestCases())
        );

        submission.setState(SubmissionState.COMPLETED);
        submission.setVerdict(result.verdict());
        submission.setStdout(result.stdout());
        submission.setStderr(result.stderr());
        submission.setRuntimeMs(result.runtimeMs());
        submission.setMemoryMb(result.memoryMb());
        submission.setUpdatedAt(Instant.now());
        submissionRepository.save(submission);
    }
}
