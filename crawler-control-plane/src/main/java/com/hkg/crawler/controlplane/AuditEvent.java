package com.hkg.crawler.controlplane;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * One operator action recorded for compliance. Audit events form a
 * SHA-256 hash chain — each event's {@code chainHash} is computed from
 * the previous event's chain hash plus this event's own content. Any
 * mid-chain tamper is detectable by re-walking the hashes.
 *
 * <p>Hash-chained audit logs are a §1 + §28 (security) requirement:
 * tampering protection without trusting the database itself.
 */
public record AuditEvent(
    long    sequenceNumber,
    Instant occurredAt,
    String  actor,
    String  actionType,
    String  targetUrl,
    String  targetHost,
    Map<String, String> details,
    String  requestId,
    String  previousChainHash,
    String  chainHash
) {

    public AuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(previousChainHash, "previousChainHash");
        Objects.requireNonNull(chainHash, "chainHash");
        details = Map.copyOf(details);
    }

    /**
     * Genesis hash — the starting state of the chain. Each fresh
     * audit log uses this as the {@code previousChainHash} of its
     * first event.
     */
    public static final String GENESIS_HASH =
        "0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Construct an event by computing its {@code chainHash} from the
     * given {@code previousChainHash} and this event's content.
     */
    public static AuditEvent createWithChain(
            long sequenceNumber, Instant occurredAt, String actor, String actionType,
            String targetUrl, String targetHost, Map<String, String> details,
            String requestId, String previousChainHash) {

        String contentDigest = computeContentDigest(
            sequenceNumber, occurredAt, actor, actionType,
            targetUrl, targetHost, details, requestId);
        String chainHash = sha256(previousChainHash + ":" + contentDigest);

        return new AuditEvent(sequenceNumber, occurredAt, actor, actionType,
            targetUrl, targetHost, details, requestId,
            previousChainHash, chainHash);
    }

    /**
     * Verify that this event's {@code chainHash} matches the
     * recomputed hash from its content. Used by a post-hoc audit
     * walker to detect tampering.
     */
    public boolean verifyChainHash() {
        String contentDigest = computeContentDigest(
            sequenceNumber, occurredAt, actor, actionType,
            targetUrl, targetHost, details, requestId);
        String expected = sha256(previousChainHash + ":" + contentDigest);
        return expected.equals(chainHash);
    }

    private static String computeContentDigest(
            long seq, Instant when, String actor, String actionType,
            String url, String host, Map<String, String> details, String reqId) {
        StringBuilder sb = new StringBuilder();
        sb.append(seq).append('|')
          .append(when.toEpochMilli()).append('|')
          .append(actor).append('|')
          .append(actionType).append('|')
          .append(url == null ? "" : url).append('|')
          .append(host == null ? "" : host).append('|')
          .append(reqId).append('|');
        // Serialize details map deterministically by sorted key.
        details.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        return sha256(sb.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
