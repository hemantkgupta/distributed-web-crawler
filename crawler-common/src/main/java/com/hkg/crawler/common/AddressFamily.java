package com.hkg.crawler.common;

/**
 * IP address family for DNS resolution and IP-level politeness clocks.
 *
 * <p>The DNS Service caches positive lookups per (Host, AddressFamily)
 * because dual-stack resolution can return different sets of IPs for
 * IPv4 vs IPv6 with different TTLs. The Fetcher requests a specific
 * family per fetch (default IPv4, with optional fallback to IPv6).
 */
public enum AddressFamily {
    IPV4,
    IPV6
}
