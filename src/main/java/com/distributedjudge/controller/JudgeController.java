package com.distributedjudge.controller;

import com.distributedjudge.dto.MetricsSnapshot;
import com.distributedjudge.dto.ProblemResponse;
import com.distributedjudge.dto.SubmissionAcceptedResponse;
import com.distributedjudge.dto.SubmissionRequest;
import com.distributedjudge.dto.SubmissionStatusResponse;
import com.distributedjudge.service.AuthService;
import com.distributedjudge.service.SubmissionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Profile("!worker")
public class JudgeController {
    private final SubmissionService submissionService;
    private final AuthService authService;

    public JudgeController(SubmissionService submissionService, AuthService authService) {
        this.submissionService = submissionService;
        this.authService = authService;
    }

    @GetMapping("/problems")
    public List<ProblemResponse> problems() {
        return submissionService.listProblems();
    }

    @GetMapping("/problems/{id}")
    public ProblemResponse problem(@PathVariable Long id) {
        return submissionService.getProblem(id);
    }

    @PostMapping("/submissions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionAcceptedResponse submit(
            @Valid @RequestBody SubmissionRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        String authenticatedEmail = authService.requireEmail(authorizationHeader);
        return submissionService.submit(request, authenticatedEmail);
    }

    @GetMapping("/submissions/{id}")
    public SubmissionStatusResponse status(
            @PathVariable Long id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        String authenticatedEmail = authService.requireEmail(authorizationHeader);
        return submissionService.getStatus(id, authenticatedEmail);
    }

    @GetMapping("/submissions")
    public List<SubmissionStatusResponse> recent() {
        return submissionService.recentSubmissions();
    }

    @GetMapping("/judge/metrics")
    public MetricsSnapshot metrics() {
        return submissionService.metrics();
    }
}
