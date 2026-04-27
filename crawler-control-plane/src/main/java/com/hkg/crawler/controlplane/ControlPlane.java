package com.hkg.crawler.controlplane;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operator-facing service surface (in-process API). The REST gateway
 * (JAX-RS or Spring Boot in production) is a thin wrapper around this
 * class — the same actions are exposed via REST + admin CLI without
 * duplicating logic.
 *
 * <p>Per blog §1: the Control Plane does not sit on the fetch path —
 * its budget is operator-latency, not crawl throughput. Every action
 * is recorded in {@link AuditLog} for compliance.
 */
public final class ControlPlane {

    private final AtomicReference<ScopeConfig> activeScope =
        new AtomicReference<>(ScopeConfig.permissive());
    private final List<ScopeConfig> scopeHistory = java.util.Collections.synchronizedList(
        new java.util.ArrayList<>());
    private final List<CanonicalUrl> seedSubmissions = java.util.Collections.synchronizedList(
        new java.util.ArrayList<>());
    private final java.util.Set<Host> quarantinedHosts = java.util.Collections.synchronizedSet(
        new java.util.HashSet<>());
    private final java.util.Set<CanonicalUrl> takedowns = java.util.Collections.synchronizedSet(
        new java.util.HashSet<>());
    private final AuditLog auditLog;
    private boolean paused = false;

    public ControlPlane(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    // ---- Seeds ---------------------------------------------------------

    /**
     * Submit a list of seed URLs. Filters them through the active
     * scope; returns a summary of accepted vs rejected.
     */
    public SeedSubmissionResult submitSeeds(List<CanonicalUrl> seeds, String actor) {
        List<CanonicalUrl> accepted = new java.util.ArrayList<>();
        List<CanonicalUrl> rejected = new java.util.ArrayList<>();
        ScopeConfig scope = activeScope.get();
        for (CanonicalUrl url : seeds) {
            if (scope.check(url) == ScopeConfig.Verdict.ALLOW) {
                accepted.add(url);
                seedSubmissions.add(url);
            } else {
                rejected.add(url);
            }
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("accepted_count", String.valueOf(accepted.size()));
        details.put("rejected_count", String.valueOf(rejected.size()));
        auditLog.record(actor, "SEED_SUBMIT", null, null, details);
        return new SeedSubmissionResult(accepted, rejected);
    }

    public List<CanonicalUrl> seedSubmissions() {
        return List.copyOf(seedSubmissions);
    }

    // ---- Scope ---------------------------------------------------------

    public ScopeConfig activeScope() { return activeScope.get(); }

    public void updateScope(ScopeConfig newScope, String actor) {
        ScopeConfig previous = activeScope.getAndSet(newScope);
        scopeHistory.add(newScope);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("from_version", String.valueOf(previous.version()));
        details.put("to_version",   String.valueOf(newScope.version()));
        details.put("config_name",  newScope.configName());
        auditLog.record(actor, "SCOPE_UPDATE", null, null, details);
    }

    public List<ScopeConfig> scopeHistory() {
        return List.copyOf(scopeHistory);
    }

    // ---- Takedowns ----------------------------------------------------

    public void takedownUrl(CanonicalUrl url, String reason, String legalBasis, String actor) {
        takedowns.add(url);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("legal_basis", legalBasis == null ? "" : legalBasis);
        auditLog.record(actor, "TAKEDOWN", url.value(), url.host().value(), details);
    }

    public boolean isTakedown(CanonicalUrl url) {
        return takedowns.contains(url);
    }

    public int takedownCount() { return takedowns.size(); }

    // ---- Host quarantine ---------------------------------------------

    public void quarantineHost(Host host, String actor) {
        quarantinedHosts.add(host);
        auditLog.record(actor, "HOST_QUARANTINE", null, host.value(), Map.of());
    }

    public void releaseHost(Host host, String actor) {
        quarantinedHosts.remove(host);
        auditLog.record(actor, "HOST_RELEASE", null, host.value(), Map.of());
    }

    public boolean isQuarantined(Host host) {
        return quarantinedHosts.contains(host);
    }

    // ---- Pause / resume -----------------------------------------------

    public void pause(String actor) {
        paused = true;
        auditLog.record(actor, "GLOBAL_PAUSE", null, null, Map.of());
    }

    public void resume(String actor) {
        paused = false;
        auditLog.record(actor, "GLOBAL_RESUME", null, null, Map.of());
    }

    public boolean isPaused() { return paused; }

    // ---- Audit -------------------------------------------------------

    public AuditLog auditLog() { return auditLog; }

    public Optional<Instant> lastActionAt() {
        List<AuditEvent> events = auditLog.snapshot();
        if (events.isEmpty()) return Optional.empty();
        return Optional.of(events.get(events.size() - 1).occurredAt());
    }

    /** Result of a seed-submission request — accepted vs rejected. */
    public record SeedSubmissionResult(
        List<CanonicalUrl> accepted,
        List<CanonicalUrl> rejected
    ) {}
}
