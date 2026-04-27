package com.hkg.crawler.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalUrlTest {

    @Test
    void lowercases_scheme_and_host() {
        CanonicalUrl u = CanonicalUrl.of("HTTP://Example.COM/Path");
        assertThat(u.value()).isEqualTo("http://example.com/Path");
        assertThat(u.scheme()).isEqualTo("http");
        assertThat(u.host().value()).isEqualTo("example.com");
        assertThat(u.path()).isEqualTo("/Path");   // path case PRESERVED (RFC 3986)
    }

    @Test
    void strips_default_ports() {
        assertThat(CanonicalUrl.of("http://example.com:80/page").value())
            .isEqualTo("http://example.com/page");
        assertThat(CanonicalUrl.of("https://example.com:443/page").value())
            .isEqualTo("https://example.com/page");
    }

    @Test
    void preserves_non_default_ports() {
        assertThat(CanonicalUrl.of("http://example.com:8080/page").value())
            .isEqualTo("http://example.com:8080/page");
    }

    @Test
    void empty_path_becomes_slash() {
        assertThat(CanonicalUrl.of("http://example.com").value())
            .isEqualTo("http://example.com/");
    }

    @Test
    void removes_dot_segments() {
        assertThat(CanonicalUrl.of("http://example.com/a/./b/../c").value())
            .isEqualTo("http://example.com/a/c");
    }

    @Test
    void drops_fragment() {
        assertThat(CanonicalUrl.of("http://example.com/page#section").value())
            .isEqualTo("http://example.com/page");
    }

    @Test
    void preserves_query_string() {
        assertThat(CanonicalUrl.of("http://example.com/page?a=1&b=2").value())
            .isEqualTo("http://example.com/page?a=1&b=2");
        assertThat(CanonicalUrl.of("http://example.com/page?a=1&b=2").query())
            .isEqualTo("a=1&b=2");
    }

    @Test
    void rejects_relative_url() {
        assertThatThrownBy(() -> CanonicalUrl.of("/just/a/path"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_non_http_scheme() {
        assertThatThrownBy(() -> CanonicalUrl.of("ftp://example.com/file"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalUrl.of("mailto:foo@example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_malformed_url() {
        assertThatThrownBy(() -> CanonicalUrl.of("http://example .com/page"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equality_via_canonicalized_form() {
        // Different surface forms; same canonical
        CanonicalUrl a = CanonicalUrl.of("HTTP://Example.com:80/a/./b/../page#frag");
        CanonicalUrl b = CanonicalUrl.of("http://example.com/a/page");
        assertThat(a).isEqualTo(b);
        assertThat(a.value()).isEqualTo("http://example.com/a/page");
    }
}
