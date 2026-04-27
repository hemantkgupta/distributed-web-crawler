package com.hkg.crawler.admin;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.controlplane.AuditLog;
import com.hkg.crawler.controlplane.ControlPlane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCliTest {

    private ControlPlane cp;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;
    private AdminCli cli;

    @BeforeEach
    void setUp() {
        cp = new ControlPlane(new AuditLog());
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        cli = new AdminCli(cp,
            new PrintStream(outBytes), new PrintStream(errBytes),
            "operator-test");
    }

    private String stdout() { return outBytes.toString(StandardCharsets.UTF_8); }
    private String stderr() { return errBytes.toString(StandardCharsets.UTF_8); }

    @Test
    void empty_args_prints_usage_with_usage_error() {
        int exit = cli.run(new String[0]);
        assertThat(exit).isEqualTo(AdminCli.EXIT_USAGE_ERROR);
        assertThat(stderr()).contains("crawler-admin");
    }

    @Test
    void unknown_command_returns_usage_error() {
        int exit = cli.run(new String[] { "frobnicate" });
        assertThat(exit).isEqualTo(AdminCli.EXIT_USAGE_ERROR);
        assertThat(stderr()).contains("unknown");
    }

    @Test
    void seed_submit_routes_to_control_plane() {
        int exit = cli.run(new String[] {
            "seed", "submit", "http://a.com/", "http://b.com/"
        });
        assertThat(exit).isEqualTo(AdminCli.EXIT_OK);
        assertThat(cp.seedSubmissions()).hasSize(2);
        assertThat(stdout()).contains("accepted: 2");
    }

    @Test
    void seed_submit_without_urls_is_usage_error() {
        int exit = cli.run(new String[] { "seed", "submit" });
        assertThat(exit).isEqualTo(AdminCli.EXIT_USAGE_ERROR);
    }

    @Test
    void takedown_records_url() {
        int exit = cli.run(new String[] {
            "takedown", "http://x.com/sensitive", "DMCA-2026-0001"
        });
        assertThat(exit).isEqualTo(AdminCli.EXIT_OK);
        assertThat(cp.isTakedown(CanonicalUrl.of("http://x.com/sensitive"))).isTrue();
    }

    @Test
    void host_quarantine_and_release() {
        int exit1 = cli.run(new String[] { "host", "quarantine", "flaky.com" });
        assertThat(exit1).isEqualTo(AdminCli.EXIT_OK);
        assertThat(cp.isQuarantined(Host.of("flaky.com"))).isTrue();

        int exit2 = cli.run(new String[] { "host", "release", "flaky.com" });
        assertThat(exit2).isEqualTo(AdminCli.EXIT_OK);
        assertThat(cp.isQuarantined(Host.of("flaky.com"))).isFalse();
    }

    @Test
    void pause_and_resume() {
        cli.run(new String[] { "pause" });
        assertThat(cp.isPaused()).isTrue();
        cli.run(new String[] { "resume" });
        assertThat(cp.isPaused()).isFalse();
    }

    @Test
    void status_prints_runtime_info() {
        cp.pause("operator");
        int exit = cli.run(new String[] { "status" });
        assertThat(exit).isEqualTo(AdminCli.EXIT_OK);
        assertThat(stdout()).contains("paused: true");
        assertThat(stdout()).contains("active_scope: permissive");
    }

    @Test
    void audit_list_shows_recent_events() {
        cli.run(new String[] { "pause" });
        cli.run(new String[] { "resume" });
        cli.run(new String[] { "host", "quarantine", "x.com" });

        outBytes.reset();
        int exit = cli.run(new String[] { "audit", "list" });
        assertThat(exit).isEqualTo(AdminCli.EXIT_OK);
        String out = stdout();
        assertThat(out).contains("GLOBAL_PAUSE");
        assertThat(out).contains("GLOBAL_RESUME");
        assertThat(out).contains("HOST_QUARANTINE");
    }

    @Test
    void audit_list_respects_limit() {
        for (int i = 0; i < 10; i++) cli.run(new String[] { "pause" });

        outBytes.reset();
        cli.run(new String[] { "audit", "list", "--limit", "3" });
        String out = stdout();
        long lines = out.lines().count();
        assertThat(lines).isLessThanOrEqualTo(3);
    }

    @Test
    void help_prints_usage_and_returns_ok() {
        int exit = cli.run(new String[] { "help" });
        assertThat(exit).isEqualTo(AdminCli.EXIT_OK);
        assertThat(stdout()).contains("crawler-admin");
    }

    @Test
    void invalid_url_in_takedown_is_usage_error() {
        int exit = cli.run(new String[] {
            "takedown", "not a url", "reason"
        });
        assertThat(exit).isEqualTo(AdminCli.EXIT_USAGE_ERROR);
    }
}
