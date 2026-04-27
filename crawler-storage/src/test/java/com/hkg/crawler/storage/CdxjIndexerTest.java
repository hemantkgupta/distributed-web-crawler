package com.hkg.crawler.storage;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdxjIndexerTest {

    @Test
    void single_entry_renders_correctly() {
        CdxjEntry entry = CdxjEntry.of(
            CanonicalUrl.of("http://example.com/page?q=1"),
            Instant.parse("2026-04-27T15:00:00Z"),
            "text/html",
            200,
            "sha1:abc",
            12345,
            "test-1.warc",
            42L);

        String line = entry.toCdxjLine();
        // SURT for example.com: "com,example"
        assertThat(line).startsWith("com,example)/page?q=1 20260427150000 ");
        assertThat(line).contains("\"url\": \"http://example.com/page?q=1\"");
        assertThat(line).contains("\"status\": \"200\"");
        assertThat(line).contains("\"filename\": \"test-1.warc\"");
        assertThat(line).contains("\"offset\": \"42\"");
    }

    @Test
    void surt_sorts_lexicographically_by_reversed_host(@TempDir Path tempDir) throws IOException {
        // Three URLs whose SURT keys should sort as: com,a < com,b < org,a
        CdxjEntry a = CdxjEntry.of(CanonicalUrl.of("http://a.com/"),
            Instant.parse("2026-04-27T15:00:00Z"), "text/html", 200, "sha1:1", 1, "f", 0);
        CdxjEntry b = CdxjEntry.of(CanonicalUrl.of("http://b.com/"),
            Instant.parse("2026-04-27T15:00:01Z"), "text/html", 200, "sha1:2", 1, "f", 100);
        CdxjEntry c = CdxjEntry.of(CanonicalUrl.of("http://a.org/"),
            Instant.parse("2026-04-27T15:00:02Z"), "text/html", 200, "sha1:3", 1, "f", 200);

        Path output = tempDir.resolve("index.cdxj");
        long count = new CdxjIndexer().writeSortedIndex(List.of(c, b, a), output);
        assertThat(count).isEqualTo(3);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("com,a)");   // a.com
        assertThat(lines.get(1)).startsWith("com,b)");   // b.com
        assertThat(lines.get(2)).startsWith("org,a)");   // a.org
    }

    @Test
    void same_surt_sorts_by_timestamp(@TempDir Path tempDir) throws IOException {
        Instant t1 = Instant.parse("2026-04-27T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-27T11:00:00Z");
        Instant t3 = Instant.parse("2026-04-27T12:00:00Z");

        CanonicalUrl url = CanonicalUrl.of("http://example.com/page");
        CdxjEntry e1 = CdxjEntry.of(url, t2, "text/html", 200, "sha1:b", 1, "f", 100);
        CdxjEntry e2 = CdxjEntry.of(url, t1, "text/html", 200, "sha1:a", 1, "f", 0);
        CdxjEntry e3 = CdxjEntry.of(url, t3, "text/html", 200, "sha1:c", 1, "f", 200);

        Path output = tempDir.resolve("ts.cdxj");
        new CdxjIndexer().writeSortedIndex(List.of(e1, e2, e3), output);

        List<String> lines = Files.readAllLines(output);
        assertThat(lines.get(0)).contains("20260427100000");
        assertThat(lines.get(1)).contains("20260427110000");
        assertThat(lines.get(2)).contains("20260427120000");
    }

    @Test
    void appendUnsorted_creates_and_extends(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("appended.cdxj");
        CdxjIndexer indexer = new CdxjIndexer();

        indexer.appendUnsorted(CdxjEntry.of(
            CanonicalUrl.of("http://example.com/a"),
            Instant.parse("2026-04-27T15:00:00Z"),
            "text/html", 200, "sha1:1", 1, "f", 0), output);
        indexer.appendUnsorted(CdxjEntry.of(
            CanonicalUrl.of("http://example.com/b"),
            Instant.parse("2026-04-27T15:00:01Z"),
            "text/html", 200, "sha1:2", 1, "f", 100), output);

        assertThat(Files.readAllLines(output)).hasSize(2);
    }
}
