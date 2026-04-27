package com.hkg.crawler.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostTest {

    @Test
    void lowercases_and_strips_trailing_dot() {
        assertThat(Host.of("Example.COM.").value()).isEqualTo("example.com");
        assertThat(Host.of("WWW.Example.org").value()).isEqualTo("www.example.org");
    }

    @Test
    void trims_whitespace() {
        assertThat(Host.of("  example.com  ").value()).isEqualTo("example.com");
    }

    @Test
    void surt_reverses_components() {
        assertThat(Host.of("sub.example.com").surt()).isEqualTo("com,example,sub");
        assertThat(Host.of("example.com").surt()).isEqualTo("com,example");
    }

    @Test
    void registrable_domain_strips_subdomain() {
        // NOTE: this is a stub implementation; real PSL lookup is required
        // for multi-label TLDs. Test reflects current behavior.
        assertThat(Host.of("a.b.example.com").registrableDomain()).isEqualTo("example.com");
        assertThat(Host.of("example.com").registrableDomain()).isEqualTo("example.com");
    }

    @Test
    void rejects_empty_or_blank() {
        assertThatThrownBy(() -> Host.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Host.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_label_starting_or_ending_with_hyphen() {
        assertThatThrownBy(() -> Host.of("-example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Host.of("example-.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_characters() {
        assertThatThrownBy(() -> Host.of("ex_ample.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Host.of("example..com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equality_and_hashcode_are_value_based() {
        Host a = Host.of("Example.com");
        Host b = Host.of("example.COM");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
