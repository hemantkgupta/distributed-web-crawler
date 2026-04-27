package com.hkg.crawler.indexer;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerPipelineTest {

    private InMemoryMessagePublisher publisher;
    private IndexerPipeline pipeline;

    @BeforeEach
    void setUp() {
        publisher = new InMemoryMessagePublisher();
        pipeline = new IndexerPipeline(publisher);
    }

    private static String decode(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Test
    void emitDocument_publishes_to_documents_stream() {
        pipeline.emitDocument(
            CanonicalUrl.of("http://example.com/page"),
            "The Title",
            "main text content",
            0xdeadbeefL,
            Instant.parse("2026-04-27T12:00:00Z"));

        assertThat(publisher.publishedCount(IndexerStream.DOCUMENTS)).isEqualTo(1);
        assertThat(publisher.publishedCount(IndexerStream.LINKS)).isZero();
        assertThat(publisher.publishedCount(IndexerStream.OPERATIONAL)).isZero();

        List<byte[]> docs = publisher.snapshot(IndexerStream.DOCUMENTS);
        String s = decode(docs.get(0));
        assertThat(s).contains("event=document");
        assertThat(s).contains("url=http://example.com/page");
        assertThat(s).contains("host=example.com");
        assertThat(s).contains("simhash64=deadbeef");
    }

    @Test
    void emitLink_publishes_to_links_stream() {
        pipeline.emitLink(
            CanonicalUrl.of("http://a.com/"),
            CanonicalUrl.of("http://b.com/dest"),
            "click here",
            "nofollow",
            "MAIN");

        assertThat(publisher.publishedCount(IndexerStream.LINKS)).isEqualTo(1);
        String s = decode(publisher.snapshot(IndexerStream.LINKS).get(0));
        assertThat(s).contains("event=link");
        assertThat(s).contains("source_url=http://a.com/");
        assertThat(s).contains("target_url=http://b.com/dest");
        assertThat(s).contains("anchor=\"click here\"");
        assertThat(s).contains("rel=nofollow");
        assertThat(s).contains("dom_section=MAIN");
    }

    @Test
    void emitOperational_publishes_to_operational_stream() {
        pipeline.emitOperational(
            CanonicalUrl.of("http://a.com/"),
            200, 123L, 4567L, "text/html",
            true, false, Instant.parse("2026-04-27T12:00:00Z"));

        assertThat(publisher.publishedCount(IndexerStream.OPERATIONAL)).isEqualTo(1);
        String s = decode(publisher.snapshot(IndexerStream.OPERATIONAL).get(0));
        assertThat(s).contains("event=fetch");
        assertThat(s).contains("http_status=200");
        assertThat(s).contains("fetch_duration_ms=123");
        assertThat(s).contains("body_size_bytes=4567");
        assertThat(s).contains("conditional_used=true");
        assertThat(s).contains("conditional_matched=false");
    }

    @Test
    void streams_are_independently_addressable() {
        pipeline.emitDocument(CanonicalUrl.of("http://a.com/"), "T", "t", 0L,
            Instant.parse("2026-04-27T12:00:00Z"));
        pipeline.emitLink(CanonicalUrl.of("http://a.com/"),
            CanonicalUrl.of("http://b.com/"), "anchor", "", "MAIN");
        pipeline.emitOperational(CanonicalUrl.of("http://a.com/"), 200, 100, 1024,
            "text/html", false, false, Instant.parse("2026-04-27T12:00:00Z"));

        assertThat(publisher.publishedCount(IndexerStream.DOCUMENTS)).isEqualTo(1);
        assertThat(publisher.publishedCount(IndexerStream.LINKS)).isEqualTo(1);
        assertThat(publisher.publishedCount(IndexerStream.OPERATIONAL)).isEqualTo(1);
    }

    @Test
    void drain_empties_the_queue() {
        pipeline.emitDocument(CanonicalUrl.of("http://a.com/1"), "T1", "x", 0L,
            Instant.parse("2026-04-27T12:00:00Z"));
        pipeline.emitDocument(CanonicalUrl.of("http://a.com/2"), "T2", "y", 0L,
            Instant.parse("2026-04-27T12:00:00Z"));

        List<byte[]> drained = publisher.drain(IndexerStream.DOCUMENTS);
        assertThat(drained).hasSize(2);
        // Subsequent drain returns empty.
        assertThat(publisher.drain(IndexerStream.DOCUMENTS)).isEmpty();
    }

    @Test
    void special_characters_in_anchor_are_quoted() {
        pipeline.emitLink(
            CanonicalUrl.of("http://a.com/"),
            CanonicalUrl.of("http://b.com/"),
            "Spaces and \"quotes\"",
            "", "");
        String s = decode(publisher.snapshot(IndexerStream.LINKS).get(0));
        assertThat(s).contains("anchor=\"Spaces and \\\"quotes\\\"\"");
    }
}
