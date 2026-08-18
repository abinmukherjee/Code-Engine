const state = {
    problems: [],
    activeProblem: null,
    activeSubmission: null,
    auth: JSON.parse(localStorage.getItem("judgeAuth") || "null"),
    started: false,
    metricTimer: null,
    recentTimer: null,
    pollHandle: null
};

const $ = (id) => document.getElementById(id);

// "wrong"/"tle" templates compile and run standalone regardless of which
// problem is selected (they never match any expected output, or genuinely
// never terminate), so a single set per language covers every problem. Only
// "accepted" needs a real, problem-specific solution now that the worker
// actually compiles and runs submissions instead of pattern-matching them.
const genericTemplates = {
    JAVA: {
        wrong: `public class Main {\n    public static void main(String[] args) {\n        System.out.println(0); // wrong\n    }\n}`,
        tle: `public class Main {\n    public static void main(String[] args) {\n        while (true) {}\n    }\n}`
    },
    PYTHON: {
        wrong: `print(0)  # wrong`,
        tle: `while True:\n    pass`
    },
    C: {
        wrong: `#include <stdio.h>\n\nint main(void) {\n    printf("0\\n"); // wrong\n    return 0;\n}`,
        tle: `int main(void) {\n    while (1) {}\n    return 0;\n}`
    },
    CPP: {
        wrong: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    cout << 0 << '\\n'; // wrong\n    return 0;\n}`,
        tle: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    while (true) {}\n    return 0;\n}`
    }
};

const acceptedTemplates = {
    "Pair Sum": {
        JAVA: `import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int a = sc.nextInt();\n        int b = sc.nextInt();\n        System.out.println(a + b);\n    }\n}`,
        PYTHON: `a, b = map(int, input().split())\nprint(a + b)`,
        C: `#include <stdio.h>\n\nint main(void) {\n    int a, b;\n    scanf("%d %d", &a, &b);\n    printf("%d\\n", a + b);\n    return 0;\n}`,
        CPP: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    int a, b;\n    cin >> a >> b;\n    cout << a + b << '\\n';\n    return 0;\n}`
    },
    "Maximum Number": {
        JAVA: `import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        int max = Integer.MIN_VALUE;\n        for (int i = 0; i < n; i++) {\n            max = Math.max(max, sc.nextInt());\n        }\n        System.out.println(max);\n    }\n}`,
        PYTHON: `n = int(input())\nnums = list(map(int, input().split()))\nprint(max(nums))`,
        C: `#include <stdio.h>\n\nint main(void) {\n    int n;\n    scanf("%d", &n);\n    int max = -2147483648;\n    for (int i = 0; i < n; i++) {\n        int x;\n        scanf("%d", &x);\n        if (x > max) max = x;\n    }\n    printf("%d\\n", max);\n    return 0;\n}`,
        CPP: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    int n;\n    cin >> n;\n    int mx = INT_MIN;\n    for (int i = 0; i < n; i++) {\n        int x;\n        cin >> x;\n        mx = max(mx, x);\n    }\n    cout << mx << '\\n';\n    return 0;\n}`
    },
    "Reverse Words": {
        JAVA: `import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        String line = sc.nextLine();\n        String[] words = line.trim().split("\\\\s+");\n        StringBuilder sb = new StringBuilder();\n        for (int i = words.length - 1; i >= 0; i--) {\n            sb.append(words[i]);\n            if (i > 0) sb.append(' ');\n        }\n        System.out.println(sb.toString());\n    }\n}`,
        PYTHON: `words = input().split()\nprint(' '.join(reversed(words)))`,
        C: `#include <stdio.h>\n#include <string.h>\n\nint main(void) {\n    char line[1024];\n    fgets(line, sizeof(line), stdin);\n    line[strcspn(line, "\\n")] = '\\0';\n\n    char *words[256];\n    int count = 0;\n    char *token = strtok(line, " ");\n    while (token != NULL) {\n        words[count++] = token;\n        token = strtok(NULL, " ");\n    }\n    for (int i = count - 1; i >= 0; i--) {\n        printf("%s%s", words[i], i > 0 ? " " : "\\n");\n    }\n    return 0;\n}`,
        CPP: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    string line;\n    getline(cin, line);\n    stringstream ss(line);\n    vector<string> words;\n    string w;\n    while (ss >> w) words.push_back(w);\n    for (int i = words.size() - 1; i >= 0; i--) {\n        cout << words[i];\n        if (i > 0) cout << ' ';\n    }\n    cout << '\\n';\n    return 0;\n}`
    }
};

async function api(path, options = {}) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (state.auth?.token) {
        headers.Authorization = `Bearer ${state.auth.token}`;
    }
    const response = await fetch(path, {
        headers,
        ...options
    });
    const data = await response.json();
    if (!response.ok) {
        const retry = response.headers.get("Retry-After");
        const suffix = retry ? ` Retry after ${retry}s.` : "";
        throw new Error(`${data.message || response.statusText}.${suffix}`);
    }
    return data;
}

async function init() {
    bindEvents();
    if (!state.auth?.token) {
        showAuthGate();
        return;
    }

    try {
        const me = await api("/api/auth/me");
        state.auth = { ...state.auth, ...me };
        localStorage.setItem("judgeAuth", JSON.stringify(state.auth));
        await startAuthenticatedApp();
    } catch (error) {
        clearAuth();
        showAuthGate("Session expired. Sign in again.");
    }
}

function bindEvents() {
    $("passwordLoginBtn").addEventListener("click", () => authenticate("password"));
    $("emailLoginBtn").addEventListener("click", () => authenticate("email"));
    $("signOutBtn").addEventListener("click", signOut);
    $("language").addEventListener("change", () => loadTemplate("accepted"));
    $("acceptedTemplate").addEventListener("click", () => loadTemplate("accepted"));
    $("wrongTemplate").addEventListener("click", () => loadTemplate("wrong"));
    $("tleTemplate").addEventListener("click", () => loadTemplate("tle"));
    $("submitBtn").addEventListener("click", submitCode);
}

async function startAuthenticatedApp() {
    if (state.started) {
        applyAuthenticatedUi();
        return;
    }

    applyAuthenticatedUi();
    await loadProblems();
    await refreshMetrics();
    await refreshRecent();
    state.metricTimer = setInterval(refreshMetrics, 1800);
    state.recentTimer = setInterval(refreshRecent, 2500);
    state.started = true;
}

function applyAuthenticatedUi() {
    $("authGate").classList.add("hidden");
    $("appShell").classList.remove("locked");
    $("currentUserName").textContent = state.auth.displayName || "Signed in";
    $("currentUserEmail").textContent = state.auth.email;
    $("email").value = state.auth.email;
    setAuthStatus(`${state.auth.displayName} signed in as ${state.auth.email}.`);
}

function showAuthGate(message = "Authentication required.") {
    $("authGate").classList.remove("hidden");
    $("appShell").classList.add("locked");
    setAuthStatus(message);
}

async function loadProblems() {
    state.problems = await api("/api/problems");
    const list = $("problemList");
    list.innerHTML = "";
    state.problems.forEach((problem) => {
        const button = document.createElement("button");
        button.className = "problem-item";
        button.type = "button";
        button.innerHTML = `<strong>${escapeHtml(problem.title)}</strong><span>${problem.difficulty} · ${problem.hiddenTests} tests</span>`;
        button.addEventListener("click", () => selectProblem(problem.id));
        list.appendChild(button);
    });
    if (state.problems.length > 0) {
        selectProblem(state.problems[0].id);
    }
}

async function authenticate(mode) {
    const email = $("email").value.trim();
    const password = $("password").value;
    if (!email) {
        setAuthStatus("Enter an email address.");
        return;
    }
    if (mode === "password" && password.length < 6) {
        setAuthStatus("Password must be at least 6 characters.");
        return;
    }

    try {
        const payload = {
            email,
            displayName: email.split("@")[0],
            password: mode === "password" ? password : null
        };
        const auth = await api(`/api/auth/${mode}`, {
            method: "POST",
            body: JSON.stringify(payload)
        });
        state.auth = auth;
        localStorage.setItem("judgeAuth", JSON.stringify(auth));
        $("password").value = "";
        await startAuthenticatedApp();
    } catch (error) {
        setAuthStatus(error.message);
    }
}

function signOut() {
    clearAuth();
    showAuthGate("Signed out. Authentication required.");
}

function clearAuth() {
    state.auth = null;
    localStorage.removeItem("judgeAuth");
    clearInterval(state.metricTimer);
    clearInterval(state.recentTimer);
    clearInterval(state.pollHandle);
    state.metricTimer = null;
    state.recentTimer = null;
    state.pollHandle = null;
    state.started = false;
}

function selectProblem(id) {
    state.activeProblem = state.problems.find((problem) => problem.id === id);
    document.querySelectorAll(".problem-item").forEach((item, index) => {
        item.classList.toggle("active", state.problems[index].id === id);
    });
    $("problemTitle").textContent = state.activeProblem.title;
    $("problemStatement").textContent = state.activeProblem.statement;
    $("sampleInput").textContent = state.activeProblem.sampleInput;
    $("sampleOutput").textContent = state.activeProblem.sampleOutput;
    loadTemplate("accepted");
}

function loadTemplate(kind) {
    const language = $("language").value;
    if (kind === "accepted") {
        const byProblem = acceptedTemplates[state.activeProblem?.title];
        $("sourceCode").value = byProblem ? byProblem[language] : genericTemplates[language].wrong;
        return;
    }
    $("sourceCode").value = genericTemplates[language][kind];
}

async function submitCode() {
    if (!state.activeProblem) {
        return;
    }
    if (!state.auth?.token) {
        setStatus("Sign in with email before submitting.");
        return;
    }

    setStatus("Submitting to API gateway...");
    setStages("validate");
    $("submitBtn").disabled = true;

    try {
        const payload = {
            problemId: state.activeProblem.id,
            language: $("language").value,
            sourceCode: $("sourceCode").value
        };
        const accepted = await api("/api/submissions", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        state.activeSubmission = accepted.submissionId;
        $("currentSubmission").textContent = `#${accepted.submissionId}`;
        setStatus(`Accepted by API gateway and pushed to ${accepted.queue}.`);
        setStages("queue");
        pollSubmission(accepted.submissionId);
    } catch (error) {
        setStatus(error.message);
        setStages("validate");
        $("submitBtn").disabled = false;
    }
}

function pollSubmission(id) {
    clearInterval(state.pollHandle);
    state.pollHandle = setInterval(async () => {
        const status = await api(`/api/submissions/${id}`);
        renderSubmission(status);
        if (status.state === "RUNNING") {
            setStages("run");
            setStatus("Execution worker is evaluating hidden tests.");
        }
        if (status.state === "COMPLETED") {
            clearInterval(state.pollHandle);
            setStages("verdict");
            setStatus(`Completed with verdict ${status.verdict}.`);
            $("submitBtn").disabled = false;
            await refreshMetrics();
            await refreshRecent();
        }
    }, 800);
}

function renderSubmission(status) {
    const verdict = $("currentVerdict");
    verdict.className = `verdict ${status.verdict}`;
    verdict.textContent = status.verdict;
    $("currentRuntime").textContent = `${status.runtimeMs} ms`;
    $("currentMemory").textContent = `${status.memoryMb} MB`;
    $("currentOutput").textContent = status.stderr || status.stdout || "Waiting for worker output.";
}

async function refreshMetrics() {
    const metrics = await api("/api/judge/metrics");
    $("runningCount").textContent = metrics.running;
    $("acceptedCount").textContent = metrics.accepted;
    $("rejectedCount").textContent = metrics.rateLimitRejections;
}

async function refreshRecent() {
    const recent = await api("/api/submissions");
    const list = $("recentList");
    if (recent.length === 0) {
        list.innerHTML = `<div class="recent-item"><strong>No submissions yet</strong><span>Submit code to start the stream.</span></div>`;
        return;
    }
    list.innerHTML = recent.map((item) => `
        <div class="recent-item">
            <strong>#${item.id} · ${item.verdict}</strong>
            <span>${item.problemTitle} · ${item.language} · ${item.runtimeMs} ms</span>
        </div>
    `).join("");
}

function setStages(active) {
    const order = ["validate", "queue", "run", "verdict"];
    const map = {
        validate: "stageValidate",
        queue: "stageQueue",
        run: "stageRun",
        verdict: "stageVerdict"
    };
    const activeIndex = order.indexOf(active);
    order.forEach((key, index) => {
        $(map[key]).classList.toggle("active", index <= activeIndex);
    });
}

function setStatus(message) {
    $("statusLine").textContent = message;
}

function setAuthStatus(message) {
    $("authStatus").textContent = message;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

init().catch((error) => setStatus(error.message));
