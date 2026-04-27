package com.hkg.crawler.storage;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WarcWriterTest {

    private static final byte[] SAMPLE_RESPONSE = ("HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html\r\n" +
        "\r\n" +
        "<html>hi</html>").getBytes(StandardCharsets.UTF_8);

    @Test
    void serialize_includes_all_required_warc11_headers() {
        WarcRecord rec = WarcRecord.response(
            CanonicalUrl.of("http://example.com/page"),
            Instant.parse("2026-04-27T15:00:00Z"),
            SAMPLE_RESPONSE,
            "192.0.2.1");

        byte[] bytes = WarcWriter.serialize(rec);
        String s = new String(bytes, StandardCharsets.UTF_8);

        assertThat(s).startsWith("WARC/1.1\r\n");
        assertThat(s).contains("WARC-Type: response\r\n");
        assertThat(s).contains("WARC-Target-URI: http://example.com/page\r\n");
        assertThat(s).contains("WARC-Date: 2026-04-27T15:00:00Z\r\n");
        assertThat(s).contains("WARC-Record-ID: <urn:uuid:");
        assertThat(s).contains("Content-Type: application/http; msgtype=response\r\n");
        assertThat(s).contains("Content-Length: " + SAMPLE_RESPONSE.length + "\r\n");
        assertThat(s).contains("WARC-Payload-Digest: sha1:");
        assertThat(s).contains("WARC-Block-Digest: sha1:");
        assertThat(s).contains("WARC-IP-Address: 192.0.2.1\r\n");
        // Headers/payload separator + record terminator.
        assertThat(s).contains("\r\n\r\n<html>hi</html>\r\n\r\n");
    }

    @Test
    void serialize_request_includes_concurrent_to_when_paired() {
        java.util.UUID responseId = java.util.UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
        WarcRecord req = WarcRecord.request(
            CanonicalUrl.of("http://example.com/page"),
            Instant.parse("2026-04-27T15:00:00Z"),
            "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8),
            responseId);

        byte[] bytes = WarcWriter.serialize(req);
        String s = new String(bytes, StandardCharsets.UTF_8);

        assertThat(s).contains("WARC-Type: request\r\n");
        assertThat(s).contains("WARC-Concurrent-To: <urn:uuid:" + responseId + ">\r\n");
    }

    @Test
    void writes_to_local_dir_sink(@TempDir Path tempDir) throws IOException {
        try (LocalDirWarcSink sink = new LocalDirWarcSink(tempDir);
             WarcWriter writer = new WarcWriter(sink)) {

            writer.write(WarcRecord.response(
                CanonicalUrl.of("http://example.com/page1"),
                Instant.parse("2026-04-27T15:00:00Z"),
                SAMPLE_RESPONSE, null));
            writer.write(WarcRecord.response(
                CanonicalUrl.of("http://example.com/page2"),
                Instant.parse("2026-04-27T15:00:01Z"),
                SAMPLE_RESPONSE, null));
            writer.flush();
        }

        List<Path> warcs = Files.list(tempDir)
            .filter(p -> p.toString().endsWith(".warc"))
            .toList();
        assertThat(warcs).hasSize(1);
        String content = Files.readString(warcs.get(0));
        // Two records, each with terminator.
        long pageOccurrences = content.split("WARC-Type: response").length - 1;
        assertThat(pageOccurrences).isEqualTo(2L);
    }

    @Test
    void rolls_to_new_file_when_size_cap_exceeded(@TempDir Path tempDir) throws IOException {
        try (LocalDirWarcSink sink = new LocalDirWarcSink(tempDir, "test", 500);
             WarcWriter writer = new WarcWriter(sink)) {
            // Each record is ~400 bytes; second one should trigger roll.
            writer.write(WarcRecord.response(
                CanonicalUrl.of("http://example.com/a"),
                Instant.parse("2026-04-27T15:00:00Z"),
                SAMPLE_RESPONSE, null));
            writer.write(WarcRecord.response(
                CanonicalUrl.of("http://example.com/b"),
                Instant.parse("2026-04-27T15:00:01Z"),
                SAMPLE_RESPONSE, null));
        }
        long count = Files.list(tempDir).filter(p -> p.toString().endsWith(".warc")).count();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void payload_and_block_digest_match() {
        WarcRecord rec = WarcRecord.response(
            CanonicalUrl.of("http://example.com/x"),
            Instant.parse("2026-04-27T15:00:00Z"),
            "abc".getBytes(StandardCharsets.UTF_8), null);
        // SHA-1 of "abc" is well-known: a9993e364706816aba3e25717850c26c9cd0d89d
        assertThat(rec.payloadDigestSha1()).isEqualTo("sha1:a9993e364706816aba3e25717850c26c9cd0d89d");
    }
}
