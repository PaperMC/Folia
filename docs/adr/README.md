# GoldLeaf Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for **GoldLeaf** (`LeafCraftDev/GoldLeaf`), a Folia `26.x+` fork focused on pluggable world storage, multi-machine sharding seams, and vanilla-faithful technical performance.

## Index

| # | Title | Status |
|---|---|---|
| [goldleaf-ADR-001](goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md) | Folia `26.3.x` Thin-SPI Fork, Barbell Performance Model, and `.lca` / `LeafShard` World-Engine Plan | Proposed |
| [goldleaf-ADR-002](goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md) | Sub-Tick Interrupt Scheduling and Latency-Tiered Read/Dispatch Routing | Proposed |
| [goldleaf-ADR-003](goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md) | Small-Machine Tick-Thread Scaling Curve & Ephemeral Region Allocation Reuse | Proposed |
| [goldleaf-ADR-004](goldleaf-ADR-004-worldgen-patching-boundary-and-gpu-offload-spi.md) | Worldgen Patching Boundary, Ecosystem Build-vs-Adopt Matrix, and Native 26.3+ GPU Offload SPI | Proposed |
| [goldleaf-ADR-005](goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md) | Native `.lca` Format (`LCA-ICD-3.0`) Adoption Across Disk, Memory, and Network | Proposed |
| [goldleaf-ADR-006](goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md) | Native Chunk Telemetry, Primitive Activity Counters, and `ChunkExecutionMask` SPI | Proposed |
| [goldleaf-ADR-007](goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md) | Off-Thread Plugin Safety Preflight and Opt-In Runtime Hazard SPI | Proposed |
| [goldleaf-ADR-008](goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md) | Deferred Mob AI Optimization Boundary and `P1..P4` Technical-Farm Parity Gate | Proposed (Deferred Post-`v0.1`) |
| [goldleaf-ADR-009](goldleaf-ADR-009-entity-tracker-scaling-off-tick-packet-encoding-and-lca-anti-xray-spi.md) | Entity Tracker Scaling, Off-Tick Packet Encoding, and `.lca`-Aware Anti-Xray SPI | Proposed |
| [goldleaf-ADR-010](goldleaf-ADR-010-region-safe-technical-client-protocol-spi.md) | Region-Safe Technical Client Protocol SPI (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`, Map World-ID) | Proposed |
| [goldleaf-ADR-011](goldleaf-ADR-011-folia-operational-ergonomics-and-region-context-watchdog-diagnostics.md) | Folia Operational Ergonomics, Region-Context Watchdog Diagnostics, and Region-Safe Admin Commands | Proposed |
| [goldleaf-ADR-012](goldleaf-ADR-012-per-player-chunk-gen-backpressure-and-decode-time-exploit-hardening.md) | Per-Player Chunk-Gen Backpressure, Decode-Time Packet/NBT Hardening, and Anti-Exploit Pacing | Proposed |

## Template

Use [ADR-TEMPLATE.md](ADR-TEMPLATE.md) when recording a new GoldLeaf architectural decision.
