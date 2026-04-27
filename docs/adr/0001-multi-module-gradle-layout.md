# ADR-0001: Multi-Module Gradle Layout, JDK 21, One Module Per Service

* **Status**: Accepted
* **Date**: 2026-04-27
* **Decider**: Hemant

## Context

We need a code structure for the distributed-web-crawler that lets each of the twelve services in the architectural blueprint evolve independently while sharing common types and build infrastructure.

Three layout options were considered:

1. **Monolith with packages** — single Gradle module, services as packages.
2. **Multi-repo (one repo per service)** — full independence per service.
3. **Multi-module (this ADR)** — single repo, multiple Gradle subprojects.

## Decision

Multi-module Gradle. Each of the twelve services in the architectural blueprint maps to a Gradle module. Module names mirror the blog's service catalog (`crawler-common`, `crawler-frontier`, etc.).

JDK 21 as the source/target version, primarily for virtual-thread support (load-bearing for the Fetcher service per `web-crawler-full.md` §6).

## Consequences

**Pros:**

* Module boundaries match the blog's service boundaries — easy to navigate from architectural decision to implementation.
* Each module compiles, tests, and ships independently; can be packaged separately for deployment.
* Single repo means atomic refactors across modules when service contracts change.
* Same pattern as the [`distributed-key-value-store`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/implementations/distributed-key-value-store.md) reference; familiar for anyone reading both projects.

**Cons:**

* More boilerplate than a monolith (per-module build files, dependency declarations).
* Subproject dependency graphs in Gradle have cold-start overhead.
* Cross-module test isolation is real but requires discipline (no leaking implementation details across `api` boundaries).

## Alternatives Considered

* **Monolith** — rejected because blog readers will want to navigate to a specific service's code without sifting through unrelated packages. Also tighter coupling encourages crossing the service boundaries this project is meant to demonstrate.
* **Multi-repo** — rejected because the cost of atomic changes across services (e.g., changing the `FetchOutcome` enum used across Fetcher and Frontier) outweighs the benefit. Multi-repo makes sense at production scale with separate teams; this is a single-author reference implementation.
