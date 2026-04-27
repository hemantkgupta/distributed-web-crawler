package com.hkg.crawler.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * Serializes {@link WarcRecord}s into the WARC 1.1 wire format and
 * writes them to a {@link WarcSink}. Per ISO 28500:2017:
 *
 * <pre>
 *   WARC/1.1\r\n
 *   WARC-Type: response\r\n
 *   WARC-Target-URI: https://example.com/page\r\n
 *   WARC-Date: 2026-04-27T15:00:00Z\r\n
 *   WARC-Record-ID: &lt;urn:uuid:...&gt;\r\n
 *   Content-Type: application/http; msgtype=response\r\n
 *   Content-Length: &lt;N&gt;\r\n
 *   WARC-Payload-Digest: sha1:...\r\n
 *   WARC-Block-Digest: sha1:...\r\n
 *   &lt;optional WARC-IP-Address, WARC-Concurrent-To&gt;\r\n
 *   \r\n
 *   &lt;N bytes of payload&gt;
 *   \r\n\r\n
 * </pre>
 *
 * <p>Records are separated by exactly two CRLF pairs (the "\r\n\r\n"
 * trailer). The writer is thread-safe (delegates to the sink's lock).
 */
public final class WarcWriter implements AutoCloseable {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final WarcSink sink;

    public WarcWriter(WarcSink sink) {
        this.sink = sink;
    }

    /** Serialize and write a single record. */
    public void write(WarcRecord record) throws IOException {
        sink.writeRecord(serialize(record));
    }

    /** Force buffered bytes to durable storage. */
    public void flush() throws IOException {
        sink.flush();
    }

    @Override
    public void close() throws IOException {
        sink.close();
    }

    /**
     * Serialize a single record to bytes. Exposed for testing and
     * for callers that want to write to a custom sink.
     */
    public static byte[] serialize(WarcRecord record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeAscii(out, "WARC/1.1\r\n");
            writeHeader(out, "WARC-Type", record.type().wireForm());
            writeHeader(out, "WARC-Target-URI", record.targetUri().value());
            writeHeader(out, "WARC-Date",
                DateTimeFormatter.ISO_INSTANT.format(record.warcDate()));
            writeHeader(out, "WARC-Record-ID", "<urn:uuid:" + record.recordId() + ">");
            writeHeader(out, "Content-Type", record.contentType());
            writeHeader(out, "Content-Length", String.valueOf(record.payload().length));
            writeHeader(out, "WARC-Payload-Digest", record.payloadDigestSha1());
            writeHeader(out, "WARC-Block-Digest", record.blockDigestSha1());
            for (var e : record.extraHeaders().entrySet()) {
                writeHeader(out, e.getKey(), e.getValue());
            }
            out.write(CRLF);                  // empty line ends headers
            out.write(record.payload());
            out.write(DOUBLE_CRLF);           // record terminator
        } catch (IOException e) {
            // ByteArrayOutputStream never throws, but we keep the signature.
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, String name, String value)
            throws IOException {
        writeAscii(out, name);
        writeAscii(out, ": ");
        writeAscii(out, value);
        out.write(CRLF);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
