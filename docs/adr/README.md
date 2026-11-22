# GoldLeaf Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for **GoldLeaf** (`LeafCraftDev/GoldLeaf`), a Folia `26.x+` fork focused on pluggable world storage, multi-machine sharding seams, and vanilla-faithful technical performance.

## Index

| # | Title | Status |
|---|---|---|
| [goldleaf-ADR-001](goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md) | Folia `26.3.x` Thin-SPI Fork, Barbell Performance Model, and `.lca` / `LeafShard` World-Engine Plan | In Progress |
| [goldleaf-ADR-002](goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md) | Sub-Tick Interrupt Scheduling and Latency-Tiered Read/Dispatch Routing | In Progress |
| [goldleaf-ADR-003](goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md) | Small-Machine Tick-Thread Scaling Curve & Ephemeral Region Allocation Reuse | Accepted |
| [goldleaf-ADR-004](goldleaf-ADR-004-worldgen-patching-boundary-and-gpu-offload-spi.md) | Worldgen Patching Boundary, Ecosystem Build-vs-Adopt Matrix, and Native 26.3+ GPU Offload SPI | In Progress |
| [goldleaf-ADR-005](goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md) | Native `.lca` Format (`LCA-ICD-3.0`) Adoption Across Disk, Memory, and Network | In Progress |
| [goldleaf-ADR-006](goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md) | Native Chunk Telemetry, Primitive Activity Counters, and `ChunkExecutionMask` SPI | In Progress |
| [goldleaf-ADR-007](goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md) | Off-Thread Plugin Safety Preflight and Opt-In Runtime Hazard SPI | In Progress |
| [goldleaf-ADR-008](goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md) | Deferred Mob AI Optimization Boundary and `P1..P4` Technical-Farm Parity Gate | Proposed (Deferred Post-`v0.1`) |
| [goldleaf-ADR-009](goldleaf-ADR-009-entity-tracker-scaling-off-tick-packet-encoding-and-lca-anti-xray-spi.md) | Entity Tracker Scaling, Off-Tick Packet Encoding, and `.lca`-Aware Anti-Xray SPI | In Progress |
| [goldleaf-ADR-010](goldleaf-ADR-010-region-safe-technical-client-protocol-spi.md) | Region-Safe Technical Client Protocol SPI (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`, Map World-ID) | In Progress |
| [goldleaf-ADR-011](goldleaf-ADR-011-folia-operational-ergonomics-and-region-context-watchdog-diagnostics.md) | Folia Operational Ergonomics, Region-Context Watchdog Diagnostics, and Region-Safe Admin Commands | In Progress |
| [goldleaf-ADR-012](goldleaf-ADR-012-per-player-chunk-gen-backpressure-and-decode-time-exploit-hardening.md) | Per-Player Chunk-Gen Backpressure, Decode-Time Packet/NBT Hardening, and Anti-Exploit Pacing | In Progress |
| [goldleaf-ADR-013](goldleaf-ADR-013-vanilla-faithful-micro-optimization-boundary-and-section-epoch-reuse.md) | Vanilla-Faithful Micro-Optimization Boundary, `sectionEpoch` Reuse, and Non-Goal Guardrails | In Progress |
| [goldleaf-ADR-014](goldleaf-ADR-014-zero-friction-migration-cli-honest-auto-tuner-and-technical-parity-surface.md) | Zero-Friction Migration CLI (`.lca <-> .mca`), Honest Auto-Tuner Config Surface, and Technical-Vanilla Parity Compatibility | In Progress |
| [goldleaf-ADR-015](goldleaf-ADR-015-region-safe-fake-player-and-bot-spi.md) | Region-Safe Fake Player / Bot SPI (`LeafFuzz` & Technical SMP Carpet-Class Workflows) | In Progress |
| [goldleaf-ADR-016](goldleaf-ADR-016-boot-shutdown-telemetry-leyden-aot-and-plugin-warmup.md) | Boot/Shutdown Telemetry, Leyden AOT Direct-Classpath Launch, and Bounded Plugin I/O Warmup | Accepted |
| [goldleaf-ADR-017](goldleaf-ADR-017-typed-ast-ir-opencl-worldgen-pipeline-and-throughput-envelope.md) | Native Typed-AST-to-IR OpenCL Worldgen Pipeline, Kernel Caching, and Chunk-Gen vs. Load Throughput Envelope | In Progress |
| [goldleaf-ADR-018](goldleaf-ADR-018-opt-in-chat-signing-disable-secure-seed-and-offline-plugin-audit-cli.md) | Zero-Boot Plugin Audit CLI, Opt-In Chat Signing Disable, and Deferred 1024-Bit Secure Seed Seams | In Progress |
| [goldleaf-ADR-019](goldleaf-ADR-019-reproducible-multi-fork-benchmark-harness-and-vanilla-parity-gate.md) | Reproducible Multi-Fork Benchmark Harness and Automated Vanilla-Parity Verification Gate | In Progress |

## Template

Use [ADR-TEMPLATE.md](ADR-TEMPLATE.md) when recording a new GoldLeaf architectural decision.
