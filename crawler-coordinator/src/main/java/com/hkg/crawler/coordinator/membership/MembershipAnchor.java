package com.hkg.crawler.coordinator.membership;

import com.hkg.crawler.coordinator.AgentId;

import java.util.Collection;
import java.util.Optional;

/**
 * Durable membership registry that anchors gossip's eventually-consistent
 * view. In production, an etcd-backed implementation: agents register on
 * startup, write heartbeats, and are demoted to {@code DEAD} when they
 * miss N consecutive heartbeat windows.
 *
 * <p>The anchor's job is to break ties in split-brain scenarios — gossip
 * alone cannot reliably distinguish "really partitioned" from "a few
 * lost packets," but a CP store like etcd can. When gossip and anchor
 * disagree, anchor wins.
 *
 * <p>{@code InMemoryMembershipAnchor} is the test/single-process impl;
 * {@code EtcdMembershipAnchor} (Phase 5 deployment) wraps an etcd
 * client.
 */
public interface MembershipAnchor extends AutoCloseable {

    /** Register or update an agent's authoritative snapshot. */
    void upsert(AgentSnapshot snapshot);

    /** Look up an agent. */
    Optional<AgentSnapshot> get(AgentId agentId);

    /** All agents currently in the registry. */
    Collection<AgentSnapshot> snapshot();

    /** Remove an agent (e.g., after operator-driven retirement). */
    void remove(AgentId agentId);

    @Override
    void close();
}
