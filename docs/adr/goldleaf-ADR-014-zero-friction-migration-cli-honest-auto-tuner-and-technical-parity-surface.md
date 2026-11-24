# goldleaf-ADR-014 — Zero-Friction Migration CLI (`.lca <-> .mca`), Honest Auto-Tuner Config Surface, and Technical-Vanilla Parity Compatibility

**Status:** Accepted
**Date:** 2026-11-21

## Context

### 1. The Two Largest Psychological Barriers to Adopting a New Fork

Even when a Paper/Folia fork offers compelling performance and storage gains, experienced administrators hesitate to migrate production worlds because of two well-earned fears from ecosystem history:

1. **World-Format Lock-In (The `.linear` Trauma):**
   - Operators burned by proprietary or buggy `.linear` region converters refuse to adopt a custom disk format unless migration is **explicitly opt-in**, **provably lossless**, **independently reversible back to vanilla `.mca`**, and testable offline via `--dry-run` before touching a live world.
   - `GoldLeafConfig.java` (`GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java`) establishes `storage.format = "lca"` (native `LCA-ICD-3.0` default for new worlds per `goldleaf-ADR-005`) paired with non-destructive auto-detection of existing `.mca` regions (`storage.auto-migrate-mca = false`, `storage.backup-migrated-mca = true`), and `goldleaf-ADR-005` specifies an MIT `.lca <-> .mca` tool. However, the **user-facing migration workflow, verification guarantees, and CLI contract** need to be elevated to a first-class architectural decision.
2. **Cargo-Cult YAML Tuning & Technical-Vanilla Parity Ambiguity:**
   - Admins routinely copy-paste outdated Reddit/Discord YAML tuning guides (`paper-global.yml`, `spigot.yml`, `bukkit.yml`) that conflict with Folia's regionized scheduler or silently break technical farms.
   - Technical SMP operators also rely on Paper's `GlobalConfiguration.UnsupportedSettings` (`GoldLeaf/paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java`, lines 168–188 — `allowPistonDuplication`, `allowPermanentBlockBreakExploits`, `allowHeadlessPistons`, `allowUnsafeEndPortalTeleportation`, `skipTripwireHookPlacementValidation`) and need clear, documented assurance that GoldLeaf preserves those exact toggles without hidden overrides.

## Decision

GoldLeaf treats **Zero-Friction Reversible Migration**, an **Honest Hardware-Aware Auto-Tuner (`goldleaf.yml`)**, and **Documented Technical-Vanilla Parity Compatibility** as flagship user-facing contracts.

### 1. Standalone MIT `.lca <-> .mca` Migration & Audit CLI (`goldleaf-migrate` / `LeafAnvil` CLI)

Ship a standalone, zero-server-dependency MIT-licensed CLI (invocable both as a standalone JAR and via `--goldleaf-migrate` on the server JAR before world boot) providing:

1. **Bidirectional Lossless Conversion (`mca -> lca` and `lca -> mca`):**
   - Converts `region/`, `entities/`, and `poi/` directories between vanilla Anvil `.mca` and `LCA-ICD-3.0` `.lca` with atomic temp-file writes + `fsync` (`S-004`).
   - Never deletes source `.mca` files by default; retains timestamped backups or writes to a separate `--output` directory until the operator explicitly confirms.
2. **`--dry-run` & `--verify` Parity Mode:**
   - `--dry-run` reports exact projected disk savings, chunk counts, corrupt/truncated source chunks, and estimated conversion wall-time without writing any files.
   - `--verify` performs a full chunk-by-chunk NBT/section semantic equivalence check (round-tripping `.mca -> .lca -> .mca` and validating `SEC-DED` ECC + chunk payload hashes) and exits non-zero on any mismatch (`S-004`).
3. **Fail-Closed Online Migration Guard (`GoldLeafConfig.Storage`):**
   - Preserve `storage.format = "lca"` (native `LCA-ICD-3.0` default for new worlds per `goldleaf-ADR-005`) together with `storage.auto-migrate-mca = false` and non-destructive auto-detection of existing `.mca` region files as permanent out-of-the-box defaults in `GoldLeafConfig.java`, so dropping the GoldLeaf JAR onto an existing Paper/Folia `.mca` server performs **zero** unsolicited disk-format mutations while new worlds use `.lca` by default.

### 2. Honest Auto-Tuner & Anti-Cargo-Cult Config Surface (`goldleaf.yml`)

1. **Thin, Self-Documenting `goldleaf.yml` (`GoldLeafConfig.java`):**
   - Every section in `config/goldleaf.yml` shall govern **infrastructure, hardware envelope, storage, network offload, or opt-in SPIs**—never gameplay mechanics.
   - `GoldLeafConfig#validate()` fails closed (`ConfigurateException`, `S-004`) on invalid formats, inverted bounds, or unrecognized enum values rather than silently clamping or ignoring typos.
2. **Hardware-Envelope Drift & Conflict Advisories (`HardwareEnvelope` + `TickThreadAutoTuner`):**
   - On startup (integrated into `goldleaf-ADR-011`'s concise startup summary), compare `HardwareEnvelope.detect()` against active `paper-global.yml` / `spigot.yml` / `goldleaf.yml` settings and emit plain-language, actionable advisories when:
     - `threaded-regions.threads` is manually pinned above physical core count (thrashing risk per `goldleaf-ADR-003`),
     - `spigot.yml` `hopper-check > 1` or aggressive entity activation ranges are detected on a server with technical protocols enabled (warning of farm-parity impact), or
     - `storage.format = "lca"` is active without recent verified backups.
   - Advisories are **strictly informational** (never silently mutating user-edited `paper-global.yml` / `spigot.yml` files).

### 3. Paper `UnsupportedSettings` Technical-Vanilla Parity Compatibility

1. **Preserve Paper's Exact Config Surface:**
   - GoldLeaf shall preserve `GlobalConfiguration.UnsupportedSettings` (`GlobalConfiguration.java:168-188`: `allowPistonDuplication`, `allowPermanentBlockBreakExploits`, `allowHeadlessPistons`, `allowUnsafeEndPortalTeleportation`, `skipTripwireHookPlacementValidation`) with identical semantics and defaults (`false`) so technical SMPs migrating from Paper/Folia keep existing `paper-global.yml` configurations intact.
2. **Zero Hidden Mechanics Overrides:**
   - GoldLeaf shall never silently override `UnsupportedSettings`, redstone update order, or TNT/piston mechanics behind an "optimization" toggle.

### 4. Mandatory Verification Gates

1. **Round-Trip Byte/NBT Parity Gate:** Automated CI test converting fixture worlds `.mca -> .lca -> .mca` via the CLI `--verify` path and asserting `100%` chunk-count and NBT semantic equivalence plus non-zero exit code on injected corruption (`S-004`).
2. **Drop-In Zero-Mutation Boot Test:** Boot GoldLeaf against a copy of a stock Paper/Folia `.mca` world with default `goldleaf.yml` (`storage.format = "lca"`, `storage.auto-migrate-mca = false`) and verify existing `.mca` files are auto-detected non-destructively with zero unsolicited conversion to `.lca`.
3. **Config Fail-Closed Unit Tests:** Extend `GoldLeafConfigTest` to cover invalid config values, hardware-advisory generation, and preservation of `UnsupportedSettings` defaults.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Enable `.lca` auto-migration by default on first boot** | Violates operator trust; dropping a server JAR in place should never irreversibly mutate world files without explicit opt-in. |
| **Auto-rewrite `paper-global.yml` / `spigot.yml` values to "optimal" presets** | Silently clobbers admin intent and breaks custom server setups; honest startup advisories achieve the same benefit safely. |
| **Duplicate Paper's `unsupported-settings` inside `goldleaf.yml`** | Creates split-brain configuration conflicts between `paper-global.yml` and `goldleaf.yml` and increases rebase friction. |

## Consequences

- **Positive:**
  - Eliminates world-lock-in anxiety with an MIT-licensed, verifiable, bidirectional `.lca <-> .mca` CLI while defaulting new worlds to `.lca` and preserving non-destructive `.mca` auto-detection (`auto-migrate-mca = false`).
  - Replaces cargo-cult tuning folklore with hardware-grounded advisories while respecting operator control.
  - Gives technical SMPs clear compatibility with Paper's `unsupported-settings` without adding Purpur-style gameplay bloat.
- **Negative / Trade-offs:**
  - Requires maintaining CLI parity with `LCA-ICD-3.0` (`LeafAnvil`) whenever `.lca` codec versions advance.

## References

- Existing GoldLeaf & Paper configuration sources:
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java` (`Storage`, `Threading`, `Diagnostics`, `validate()`)
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/HardwareEnvelope.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/threading/TickThreadAutoTuner.java`
  - `GoldLeaf/paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java` (`UnsupportedSettings`, lines 168–188)
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-011-folia-operational-ergonomics-and-region-context-watchdog-diagnostics.md`
  - `docs/dev/RULES.md` (`S-004`)
