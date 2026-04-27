package com.hkg.crawler.admin;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.controlplane.AuditEvent;
import com.hkg.crawler.controlplane.AuditLog;
import com.hkg.crawler.controlplane.ControlPlane;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Operator command-line interface — a thin wrapper over
 * {@link ControlPlane}. Each subcommand mirrors a REST endpoint so the
 * operator can drive the same actions either way.
 *
 * <p>Subcommands:
 *
 * <pre>
 *   crawler-admin seed submit URL [URL ...]
 *   crawler-admin takedown URL REASON
 *   crawler-admin host quarantine HOST
 *   crawler-admin host release    HOST
 *   crawler-admin pause
 *   crawler-admin resume
 *   crawler-admin status
 *   crawler-admin audit list [--limit N]
 * </pre>
 *
 * <p>This is the in-process facade; a separate launcher main can wire
 * up real I/O. Returning structured exit codes lets tests verify
 * behavior precisely.
 */
public final class AdminCli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE_ERROR = 64;     // EX_USAGE
    public static final int EXIT_RUNTIME_ERROR = 70;   // EX_SOFTWARE

    private final ControlPlane controlPlane;
    private final PrintStream out;
    private final PrintStream err;
    private final String invokingActor;

    public AdminCli(ControlPlane controlPlane, PrintStream out, PrintStream err, String invokingActor) {
        this.controlPlane = controlPlane;
        this.out = out;
        this.err = err;
        this.invokingActor = invokingActor;
    }

    /**
     * Dispatch {@code args} to the matching subcommand. Returns a
     * Unix-style exit code: 0 for success, 64 for usage error, 70
     * for runtime error.
     */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage(err);
            return EXIT_USAGE_ERROR;
        }
        try {
            return switch (args[0]) {
                case "seed"      -> handleSeed(rest(args));
                case "takedown"  -> handleTakedown(rest(args));
                case "host"      -> handleHost(rest(args));
                case "pause"     -> { controlPlane.pause(invokingActor); out.println("paused"); yield EXIT_OK; }
                case "resume"    -> { controlPlane.resume(invokingActor); out.println("resumed"); yield EXIT_OK; }
                case "status"    -> handleStatus();
                case "audit"     -> handleAudit(rest(args));
                case "help", "--help", "-h" -> { printUsage(out); yield EXIT_OK; }
                default          -> { err.println("unknown command: " + args[0]); yield EXIT_USAGE_ERROR; }
            };
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return EXIT_USAGE_ERROR;
        } catch (Exception e) {
            err.println("runtime error: " + e.getMessage());
            return EXIT_RUNTIME_ERROR;
        }
    }

    // ---- subcommand handlers ------------------------------------------

    private int handleSeed(String[] args) {
        if (args.length < 2 || !args[0].equals("submit")) {
            err.println("usage: seed submit URL [URL ...]");
            return EXIT_USAGE_ERROR;
        }
        List<CanonicalUrl> urls = Arrays.stream(args).skip(1)
            .map(CanonicalUrl::of)
            .toList();
        ControlPlane.SeedSubmissionResult r =
            controlPlane.submitSeeds(urls, invokingActor);
        out.println("accepted: " + r.accepted().size());
        out.println("rejected: " + r.rejected().size());
        for (CanonicalUrl u : r.rejected()) out.println("  rejected: " + u.value());
        return EXIT_OK;
    }

    private int handleTakedown(String[] args) {
        if (args.length < 2) {
            err.println("usage: takedown URL REASON");
            return EXIT_USAGE_ERROR;
        }
        CanonicalUrl url = CanonicalUrl.of(args[0]);
        String reason = args[1];
        controlPlane.takedownUrl(url, reason, null, invokingActor);
        out.println("takedown recorded: " + url.value());
        return EXIT_OK;
    }

    private int handleHost(String[] args) {
        if (args.length < 2) {
            err.println("usage: host (quarantine|release) HOST");
            return EXIT_USAGE_ERROR;
        }
        Host host = Host.of(args[1]);
        switch (args[0]) {
            case "quarantine" -> {
                controlPlane.quarantineHost(host, invokingActor);
                out.println("quarantined: " + host.value());
            }
            case "release" -> {
                controlPlane.releaseHost(host, invokingActor);
                out.println("released: " + host.value());
            }
            default -> {
                err.println("unknown host action: " + args[0]);
                return EXIT_USAGE_ERROR;
            }
        }
        return EXIT_OK;
    }

    private int handleStatus() {
        out.println("paused: " + controlPlane.isPaused());
        out.println("active_scope: " + controlPlane.activeScope().configName()
            + " (v" + controlPlane.activeScope().version() + ")");
        out.println("seeds_submitted: " + controlPlane.seedSubmissions().size());
        out.println("takedowns: " + controlPlane.takedownCount());
        controlPlane.lastActionAt().ifPresent(t ->
            out.println("last_action_at: " + t));
        return EXIT_OK;
    }

    private int handleAudit(String[] args) {
        int limit = 50;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--limit".equals(args[i])) {
                try { limit = Integer.parseInt(args[i + 1]); }
                catch (NumberFormatException e) {
                    err.println("invalid --limit value: " + args[i + 1]);
                    return EXIT_USAGE_ERROR;
                }
            }
        }
        AuditLog log = controlPlane.auditLog();
        List<AuditEvent> events = log.snapshot();
        int start = Math.max(0, events.size() - limit);
        for (int i = start; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            out.println(String.format("[%d] %s %s by %s target=%s",
                e.sequenceNumber(), e.occurredAt(), e.actionType(),
                e.actor(),
                e.targetUrl() != null ? e.targetUrl()
                    : e.targetHost() != null ? e.targetHost() : "-"));
        }
        return EXIT_OK;
    }

    private void printUsage(PrintStream stream) {
        stream.println("crawler-admin <command> [args...]");
        stream.println();
        stream.println("Commands:");
        stream.println("  seed submit URL [URL ...]      Submit seed URLs");
        stream.println("  takedown URL REASON            Record a takedown");
        stream.println("  host quarantine HOST           Quarantine a host");
        stream.println("  host release    HOST           Release a host");
        stream.println("  pause / resume                 Global crawl pause / resume");
        stream.println("  status                         Print runtime status");
        stream.println("  audit list [--limit N]         Show recent audit events");
    }

    private static String[] rest(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
