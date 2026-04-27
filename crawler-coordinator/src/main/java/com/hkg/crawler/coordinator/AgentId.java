package com.hkg.crawler.coordinator;

import java.util.Objects;

/**
 * Stable identifier for one crawler agent in the cluster. The agent ID
 * is durable across process restarts (encoded in {@code etcd}); only
 * its generation counter advances when the agent rejoins after a crash.
 *
 * <p>The ring routes hosts to agents by ID; gossip propagates agent
 * snapshots keyed by ID; cross-agent forwarding addresses the destination
 * by ID + advertised network endpoint.
 */
public record AgentId(String value) {
    public AgentId {
        Objects.requireNonNull(value, "agent id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("agent id must not be blank");
        }
    }
    public static AgentId of(String s) { return new AgentId(s); }
    @Override public String toString() { return value; }
}
