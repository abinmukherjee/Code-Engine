package com.distributedjudge.service;

import com.distributedjudge.config.JudgeProperties;
import com.distributedjudge.model.Language;
import com.distributedjudge.model.TestCase;
import com.distributedjudge.model.Verdict;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Compiles/runs submitted source code inside a short-lived, hardened Docker
 * container per submission.
 *
 * File transfer (source code, per-test-case stdin) goes through `docker exec`
 * writing base64-encoded content to files (`echo '<b64>' | base64 -d > file`),
 * never through docker-java's exec stdin-attach or copyArchiveToContainerCmd:
 * - copyArchiveToContainerCmd (docker cp) refuses to write into any path
 *   inside a --read-only container, even a writable tmpfs mount, since Docker
 *   only checks the container-wide read-only flag, not the actual mount at
 *   the destination.
 * - exec stdin-attach (withStdIn) hangs indefinitely on this docker-java
 *   version's httpclient5 transport: HijackingHttpRequestExecutor uploads the
 *   stdin stream with `Content-Length: Long.MAX_VALUE`, so EOF on our input
 *   never translates into an actual end-of-body signal — the process's stdin
 *   is never closed, so anything reading until EOF blocks until the in-shell
 *   `timeout` wrapper kills it.
 * Base64-in-command-args sidesteps both: no copy-into-readonly-fs, no stdin
 * attach, and no shell-injection risk from arbitrary user source code (the
 * base64 alphabet contains no shell metacharacters).
 */
@Service
@Profile("!gateway")
public class SandboxExecutor {
    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);
    private static final String WORKDIR = "/workspace";
    private static final int OUTPUT_LIMIT = 4000;

    private final JudgeProperties properties;
    private final DockerClient dockerClient;
    private final Set<String> imagesKnownPresent = ConcurrentHashMap.newKeySet();

    public SandboxExecutor(JudgeProperties properties, DockerClient dockerClient) {
        this.properties = properties;
        this.dockerClient = dockerClient;
    }

    public SandboxResult execute(Language language, String sourceCode, List<TestCase> testCases) {
        long started = System.nanoTime();
        LanguageProfile profile = LanguageProfile.forLanguage(language, properties);
        String containerId = null;
        long timeoutMs = properties.getTimeoutMs();

        try {
            containerId = createSandboxContainer(profile.image());
            dockerClient.startContainerCmd(containerId).exec();

            ExecOutcome write = writeFile(containerId, profile.sourceFileName(), sourceCode, timeoutMs);
            if (write.exitCode() != 0) {
                return new SandboxResult(Verdict.RUNTIME_ERROR, "",
                        "Sandbox setup failed: " + truncate(write.stderr()), elapsedMs(started), 0);
            }

            if (profile.compileCommand() != null) {
                ExecOutcome compile = runExec(containerId, profile.compileCommand(), timeoutMs);
                if (compile.exitCode() != 0) {
                    return new SandboxResult(Verdict.COMPILE_ERROR, "", truncate(compile.stderr()), elapsedMs(started), 0);
                }
            }

            for (TestCase testCase : testCases) {
                SandboxResult failure = runTestCase(containerId, profile, testCase, timeoutMs, started);
                if (failure != null) {
                    return failure;
                }
            }

            return new SandboxResult(Verdict.ACCEPTED, "", "", elapsedMs(started), properties.getMemoryLimitMb());
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
                } catch (Exception ignored) {
                    // Best-effort cleanup; the container will still be reaped by Docker eventually.
                }
            }
        }
    }

    private SandboxResult runTestCase(String containerId, LanguageProfile profile, TestCase testCase, long timeoutMs, long started) {
        ExecOutcome writeInput = writeFile(containerId, "input.txt", testCase.getInputData(), timeoutMs);
        if (writeInput.exitCode() != 0) {
            return new SandboxResult(Verdict.RUNTIME_ERROR, "",
                    "Sandbox setup failed: " + truncate(writeInput.stderr()), elapsedMs(started), 0);
        }

        long timeoutSeconds = Math.max(1, (timeoutMs + 999) / 1000);
        String runCommand = "timeout " + timeoutSeconds + " " + profile.runCommand() + " < input.txt";
        ExecOutcome outcome = runExec(containerId, runCommand, timeoutMs);

        boolean oomKilled = isOomKilled(containerId);
        if (oomKilled || outcome.exitCode() == 137) {
            return new SandboxResult(Verdict.MEMORY_LIMIT_EXCEEDED, "",
                    "Container exceeded " + properties.getMemoryLimitMb() + " MB", elapsedMs(started), properties.getMemoryLimitMb());
        }
        if (outcome.timedOut() || outcome.exitCode() == 124 || outcome.exitCode() == 143) {
            // 124 = GNU coreutils `timeout` convention (Debian-based images);
            // 143 = 128+SIGTERM, what Alpine's BusyBox `timeout` reports instead
            // (verified directly: busybox timeout does not use the 124 convention).
            return new SandboxResult(Verdict.TIME_LIMIT_EXCEEDED, "",
                    "Execution exceeded " + timeoutMs + " ms", elapsedMs(started), properties.getMemoryLimitMb());
        }
        if (outcome.exitCode() != 0) {
            return new SandboxResult(Verdict.RUNTIME_ERROR, "", truncate(outcome.stderr()), elapsedMs(started), 0);
        }
        if (!outcome.stdout().strip().equals(testCase.getExpectedOutput().strip())) {
            return new SandboxResult(Verdict.WRONG_ANSWER, truncate(outcome.stdout()), "", elapsedMs(started), properties.getMemoryLimitMb());
        }
        return null;
    }

    /**
     * The container-create API doesn't auto-pull missing images the way
     * `docker run` from the CLI does — a 404 "No such image" surfaces at
     * container-create time instead, so the first submission per language
     * (per worker, per image) pulls it here before creating the sandbox.
     */
    private void ensureImagePresent(String image) {
        if (imagesKnownPresent.contains(image)) {
            return;
        }
        boolean present = dockerClient.listImagesCmd().exec().stream()
                .filter(candidate -> candidate.getRepoTags() != null)
                .flatMap(candidate -> Arrays.stream(candidate.getRepoTags()))
                .anyMatch(image::equals);
        if (!present) {
            log.info("Pulling sandbox image {} (first use)", image);
            try {
                dockerClient.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion(5, TimeUnit.MINUTES);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling sandbox image " + image, ex);
            }
        }
        imagesKnownPresent.add(image);
    }

    private String createSandboxContainer(String image) {
        ensureImagePresent(image);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(properties.getMemoryLimitMb() * 1024L * 1024L)
                .withMemorySwap(properties.getMemoryLimitMb() * 1024L * 1024L)
                .withCpuPeriod(100_000L)
                .withCpuQuota(100_000L)
                .withPidsLimit(64L)
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                // "exec" is required explicitly — without it the tmpfs mount
                // defaults to noexec, which silently blocks running compiled
                // C/C++ binaries out of /workspace (interpreted languages are
                // unaffected since java/python3 themselves live outside this
                // mount, only the freshly-compiled ./out binary lives here).
                .withTmpFs(Map.of(WORKDIR, "rw,exec,size=64m,mode=1777"));

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withCmd("sh", "-c", "while :; do sleep 3600; done")
                .withWorkingDir(WORKDIR)
                .withUser("1000:1000")
                .withHostConfig(hostConfig)
                .exec();
        return container.getId();
    }

    private ExecOutcome writeFile(String containerId, String filename, String content, long timeoutMs) {
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return runExec(containerId, "echo '" + base64 + "' | base64 -d > " + filename, timeoutMs);
    }

    private ExecOutcome runExec(String containerId, String shellCommand, long timeoutMs) {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", shellCommand)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        boolean completed;
        try {
            completed = dockerClient.execStartCmd(exec.getId())
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion(timeoutMs + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            completed = false;
        }

        Long exitCode = null;
        for (int attempt = 0; attempt < 20 && exitCode == null; attempt++) {
            exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            if (exitCode == null) {
                sleepQuietly(50);
            }
        }

        return new ExecOutcome(
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8),
                exitCode == null ? -1 : exitCode,
                !completed || exitCode == null
        );
    }

    private boolean isOomKilled(String containerId) {
        try {
            return Boolean.TRUE.equals(dockerClient.inspectContainerCmd(containerId).exec().getState().getOOMKilled());
        } catch (Exception ex) {
            return false;
        }
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(1, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private String truncate(String value) {
        return value.length() > OUTPUT_LIMIT ? value.substring(0, OUTPUT_LIMIT) : value;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record ExecOutcome(String stdout, String stderr, long exitCode, boolean timedOut) {
    }

    private record LanguageProfile(String image, String sourceFileName, String compileCommand, String runCommand) {
        static LanguageProfile forLanguage(Language language, JudgeProperties properties) {
            // Heap is capped below the container's cgroup memory limit, not
            // equal to it — the JVM needs headroom beyond -Xmx for Metaspace,
            // thread stacks, and JIT/GC bookkeeping, or it thrashes trying to
            // stay under a limit that was never achievable in the first place.
            int javaHeapMb = Math.max(64, (int) (properties.getMemoryLimitMb() * 0.7));
            return switch (language) {
                case JAVA -> new LanguageProfile(properties.getJavaImage(), "Main.java", "javac Main.java",
                        "java -Xmx" + javaHeapMb + "m Main");
                case PYTHON -> new LanguageProfile(properties.getPythonImage(), "main.py", null, "python3 main.py");
                case C -> new LanguageProfile(properties.getCImage(), "main.c", "gcc -O2 -o out main.c", "./out");
                case CPP -> new LanguageProfile(properties.getCppImage(), "main.cpp",
                        "g++ -O2 -std=c++17 -o out main.cpp", "./out");
            };
        }
    }
}
