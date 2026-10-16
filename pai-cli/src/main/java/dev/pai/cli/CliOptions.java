/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.util.Optional;
import java.util.Set;

/**
 * Parsed command line.
 *
 * @param provider provider id: anthropic, openai-compat or fake
 * @param model model id
 * @param baseUrl API base URL prefix
 * @param apiKey API key
 * @param resume whether to resume a session
 * @param resumeId session id to resume; most recent when empty
 * @param cwd working directory
 * @param help whether usage was requested
 * @param approval approval mode for the approvals extension: ask, auto-read, plan or yolo
 * @param print the prompt of a single non-interactive run ({@code --print})
 * @param quiet whether only the final assistant text goes to stdout
 * @param maxTurns turn bound per run
 * @param noExt ids of extensions not to load
 */
record CliOptions(Optional<String> provider, Optional<String> model, Optional<String> baseUrl, Optional<String> apiKey,
    boolean resume, Optional<String> resumeId, Optional<String> cwd, boolean help, Optional<String> approval,
    Optional<String> print, boolean quiet, Optional<Integer> maxTurns, Set<String> noExt) {

    /** Accepted values of {@code --approval}. */
    static final Set<String> APPROVAL_MODES = Set.of("ask", "auto-read", "plan", "yolo");

    static final String USAGE = String.join("\n",
        "usage: pai [--provider anthropic|openai-compat|fake] [--model <id>] [--base-url <url>]",
        "           [--api-key <key>] [--resume [<sessionId>]] [--cwd <dir>] [--approval ask|auto-read|plan|yolo]",
        "           [--print|-p <prompt>] [--quiet] [--max-turns <n>] [--no-ext <id,...>]",
        "print: one run, no questions answered (non-read tools need --approval yolo); exit 0 completed, 2 max turns,",
        "       3 aborted, 4 prompt too long, 5 output limit, 6 provider error, 7 hook error, 64 usage error, 1 crash",
        "keys: PAI_ANTHROPIC_API_KEY, PAI_OPENAI_COMPAT_API_KEY, PAI_OPENAI_COMPAT_BASE_URL from the environment,",
        "      <cwd>/.env, ~/.pai/.env in that order; a .env base URL applies only with the key of the same file",
        "sub-agents: spawn_agent (types general/explore/reviewer, ~/.pai/agents/*.md); PAI_SUBAGENT_MAX_CONCURRENCY");

    static CliOptions parse(String[] args) {
        Optional<String> provider = Optional.empty();
        Optional<String> model = Optional.empty();
        Optional<String> baseUrl = Optional.empty();
        Optional<String> apiKey = Optional.empty();
        Optional<String> cwd = Optional.empty();
        Optional<String> resumeId = Optional.empty();
        boolean resume = false;
        boolean help = false;
        Optional<String> approval = Optional.empty();
        Optional<String> print = Optional.empty();
        boolean quiet = false;
        Optional<Integer> maxTurns = Optional.empty();
        Set<String> noExt = Set.of();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--provider" -> provider = Optional.of(value(args, ++i, a));
                case "--model" -> model = Optional.of(value(args, ++i, a));
                case "--base-url" -> baseUrl = Optional.of(value(args, ++i, a));
                case "--api-key" -> apiKey = Optional.of(value(args, ++i, a));
                case "--cwd" -> cwd = Optional.of(value(args, ++i, a));
                case "--resume" -> {
                    resume = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        resumeId = Optional.of(args[++i]);
                    }
                }
                case "--approval" -> approval = Optional.of(approval(value(args, ++i, a)));
                case "--print", "-p" -> print = Optional.of(value(args, ++i, a));
                case "--quiet" -> quiet = true;
                case "--max-turns" -> maxTurns = Optional.of(Integer.parseInt(value(args, ++i, a)));
                case "--no-ext" -> noExt = Set.of(value(args, ++i, a).split(","));
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown option: " + a);
            }
        }
        if (maxTurns.orElse(1) < 1) {
            throw new IllegalArgumentException("--max-turns must be a positive integer");
        }
        return new CliOptions(provider, model, baseUrl, apiKey, resume, resumeId, cwd, help, approval, print, quiet,
            maxTurns, noExt);
    }

    private static String approval(String mode) {
        if (!APPROVAL_MODES.contains(mode)) {
            throw new IllegalArgumentException("--approval must be ask, auto-read, plan or yolo");
        }
        return mode;
    }

    private static String value(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        return args[i];
    }
}
