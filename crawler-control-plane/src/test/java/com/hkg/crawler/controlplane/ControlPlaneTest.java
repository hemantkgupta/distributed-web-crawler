package com.hkg.crawler.controlplane;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneTest {

    private AuditLog audit;
    private ControlPlane cp;

    @BeforeEach
    void setUp() {
        audit = new AuditLog();
        cp = new ControlPlane(audit);
    }

    @Test
    void seed_submit_filters_by_scope_allowlist() {
        ScopeConfig allowOnlyExample = new ScopeConfig(1, "example-only",
            List.of(new ScopeConfig.HostRule("example.com")),
            List.of(),
            ScopeConfig.DEFAULT_MAX_DEPTH,
            List.of("text/html"));
        cp.updateScope(allowOnlyExample, "operator@example.com");

        ControlPlane.SeedSubmissionResult result = cp.submitSeeds(List.of(
            CanonicalUrl.of("http://example.com/a"),
            CanonicalUrl.of("http://other.com/b")
        ), "operator@example.com");

        assertThat(result.accepted()).hasSize(1);
        assertThat(result.accepted().get(0).host().value()).isEqualTo("example.com");
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().get(0).host().value()).isEqualTo("other.com");
    }

    @Test
    void scope_denylist_takes_precedence_over_allowlist() {
        ScopeConfig scope = new ScopeConfig(1, "denylist-test",
            List.of(new ScopeConfig.HostRule("*.example.com")),
            List.of(new ScopeConfig.HostRule("bad.example.com")),
            ScopeConfig.DEFAULT_MAX_DEPTH,
            List.of("text/html"));
        cp.updateScope(scope, "operator");

        assertThat(scope.check(CanonicalUrl.of("http://good.example.com/")))
            .isEqualTo(ScopeConfig.Verdict.ALLOW);
        assertThat(scope.check(CanonicalUrl.of("http://bad.example.com/")))
            .isEqualTo(ScopeConfig.Verdict.DENY);
    }

    @Test
    void wildcard_pattern_matches_subdomains() {
        ScopeConfig.HostRule rule = new ScopeConfig.HostRule("*.example.com");
        assertThat(rule.matches(Host.of("foo.example.com"))).isTrue();
        assertThat(rule.matches(Host.of("a.b.example.com"))).isTrue();
        assertThat(rule.matches(Host.of("example.com"))).isFalse();   // bare host doesn't match wildcard
        assertThat(rule.matches(Host.of("malicious-example.com"))).isFalse();
    }

    @Test
    void exact_pattern_matches_only_exact_host() {
        ScopeConfig.HostRule rule = new ScopeConfig.HostRule("example.com");
        assertThat(rule.matches(Host.of("example.com"))).isTrue();
        assertThat(rule.matches(Host.of("foo.example.com"))).isFalse();
    }

    @Test
    void permissive_scope_accepts_any_host() {
        ControlPlane.SeedSubmissionResult result = cp.submitSeeds(List.of(
            CanonicalUrl.of("http://anything.com/"),
            CanonicalUrl.of("http://other.org/")
        ), "operator");
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void takedown_records_url_and_audit_event() {
        CanonicalUrl url = CanonicalUrl.of("http://example.com/sensitive");
        cp.takedownUrl(url, "DMCA copyright complaint", "DMCA-2026-0001", "compliance@example.com");

        assertThat(cp.isTakedown(url)).isTrue();
        assertThat(cp.takedownCount()).isEqualTo(1);

        // Audit should have one TAKEDOWN event.
        List<AuditEvent> events = audit.snapshot();
        assertThat(events).anyMatch(e -> e.actionType().equals("TAKEDOWN"));
    }

    @Test
    void quarantine_release_round_trip() {
        Host host = Host.of("flaky.com");
        cp.quarantineHost(host, "operator");
        assertThat(cp.isQuarantined(host)).isTrue();
        cp.releaseHost(host, "operator");
        assertThat(cp.isQuarantined(host)).isFalse();
    }

    @Test
    void pause_resume_round_trip() {
        assertThat(cp.isPaused()).isFalse();
        cp.pause("operator");
        assertThat(cp.isPaused()).isTrue();
        cp.resume("operator");
        assertThat(cp.isPaused()).isFalse();
    }

    @Test
    void audit_chain_links_events() {
        cp.submitSeeds(List.of(CanonicalUrl.of("http://example.com/")), "actor1");
        cp.takedownUrl(CanonicalUrl.of("http://x.com/"), "reason", null, "actor2");
        cp.pause("actor3");

        List<AuditEvent> events = audit.snapshot();
        assertThat(events).hasSize(3);
        // First event references genesis hash.
        assertThat(events.get(0).previousChainHash()).isEqualTo(AuditEvent.GENESIS_HASH);
        // Each subsequent event references the previous chain hash.
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).previousChainHash())
                .isEqualTo(events.get(i - 1).chainHash());
        }
    }

    @Test
    void audit_log_detects_no_tampering_in_intact_chain() {
        cp.submitSeeds(List.of(CanonicalUrl.of("http://example.com/")), "actor1");
        cp.pause("actor2");
        cp.resume("actor3");

        assertThat(audit.findFirstTamperedEvent()).isEqualTo(-1);
    }

    @Test
    void scope_history_records_each_update() {
        cp.updateScope(new ScopeConfig(2, "v2", List.of(), List.of(), 30,
            List.of("text/html")), "operator");
        cp.updateScope(new ScopeConfig(3, "v3", List.of(), List.of(), 30,
            List.of("text/html")), "operator");
        assertThat(cp.scopeHistory()).hasSize(2);
        assertThat(cp.activeScope().version()).isEqualTo(3);
    }

    @Test
    void audit_event_constructor_validation() {
        AuditEvent e = AuditEvent.createWithChain(1, java.time.Instant.now(),
            "actor", "TEST", null, null, Map.of(),
            "req-1", AuditEvent.GENESIS_HASH);
        assertThat(e.verifyChainHash()).isTrue();
        assertThat(e.chainHash()).hasSize(64);   // SHA-256 hex = 64 chars
    }
}
