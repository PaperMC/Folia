package dev.leafcraft.goldleaf.perf;

/**
 * Vanilla-exact container mutation epoch and {@code LevelChunkSection#paper$getSectionEpoch()}
 * invalidation cache for idle hoppers ({@code goldleaf-ADR-013}).
 *
 * <p>Strictly confined to the hopper's owning Folia region thread ({@code F-001}..{@code F-003}).
 * A cached no-transfer result is valid <em>only</em> when:
 * <ol>
 *   <li>the hopper's own container change count has not changed,</li>
 *   <li>the neighbor container's change count has not changed,</li>
 *   <li>the neighbor container identity matches, and</li>
 *   <li>the neighbor chunk section's {@code sectionEpoch} has not changed.</li>
 * </ol>
 * Any inventory mutation ({@code setChanged()}), block-state change, container replacement,
 * facing change, or block-entity removal invalidates the cache immediately, preserving 100%
 * vanilla item-transfer order, comparator pulse widths, and {@code hopperTransfer} timing.</p>
 */
public final class HopperExactCache {

    private boolean pushNoOpValid;
    private int pushHopperChangeCount;
    private int pushDestChangeCount;
    private int pushDestSectionEpoch;
    private int pushDestIdentityHash;

    private boolean pullNoOpValid;
    private int pullHopperChangeCount;
    private int pullSourceChangeCount;
    private int pullSourceSectionEpoch;
    private int pullSourceIdentityHash;

    /**
     * Returns {@code true} if a previous push attempt failed (e.g. destination full or
     * incompatible) and neither the hopper, the destination container, nor the destination chunk
     * section has mutated since that check.
     */
    public boolean canSkipPushScan(
        final int hopperChangeCount,
        final int destChangeCount,
        final int destSectionEpoch,
        final Object destContainer
    ) {
        if (!this.pushNoOpValid || destContainer == null) {
            return false;
        }
        return this.pushHopperChangeCount == hopperChangeCount
            && this.pushDestChangeCount == destChangeCount
            && this.pushDestSectionEpoch == destSectionEpoch
            && this.pushDestIdentityHash == System.identityHashCode(destContainer);
    }

    /**
     * Records a failed push attempt so subsequent ticks can skip redundant destination slot scans
     * until any container or section mutation occurs.
     */
    public void recordPushNoOp(
        final int hopperChangeCount,
        final int destChangeCount,
        final int destSectionEpoch,
        final Object destContainer
    ) {
        if (destContainer == null) {
            invalidatePush();
            return;
        }
        this.pushHopperChangeCount = hopperChangeCount;
        this.pushDestChangeCount = destChangeCount;
        this.pushDestSectionEpoch = destSectionEpoch;
        this.pushDestIdentityHash = System.identityHashCode(destContainer);
        this.pushNoOpValid = true;
    }

    /**
     * Returns {@code true} if a previous pull attempt failed (e.g. source empty or hopper full)
     * and neither the hopper, the source container, nor the source chunk section has mutated.
     */
    public boolean canSkipPullScan(
        final int hopperChangeCount,
        final int sourceChangeCount,
        final int sourceSectionEpoch,
        final Object sourceContainer
    ) {
        if (!this.pullNoOpValid || sourceContainer == null) {
            return false;
        }
        return this.pullHopperChangeCount == hopperChangeCount
            && this.pullSourceChangeCount == sourceChangeCount
            && this.pullSourceSectionEpoch == sourceSectionEpoch
            && this.pullSourceIdentityHash == System.identityHashCode(sourceContainer);
    }

    /**
     * Records a failed pull attempt so subsequent ticks can skip redundant source slot scans
     * until any container or section mutation occurs.
     */
    public void recordPullNoOp(
        final int hopperChangeCount,
        final int sourceChangeCount,
        final int sourceSectionEpoch,
        final Object sourceContainer
    ) {
        if (sourceContainer == null) {
            invalidatePull();
            return;
        }
        this.pullHopperChangeCount = hopperChangeCount;
        this.pullSourceChangeCount = sourceChangeCount;
        this.pullSourceSectionEpoch = sourceSectionEpoch;
        this.pullSourceIdentityHash = System.identityHashCode(sourceContainer);
        this.pullNoOpValid = true;
    }

    public void invalidatePush() {
        this.pushNoOpValid = false;
        this.pushDestIdentityHash = 0;
    }

    public void invalidatePull() {
        this.pullNoOpValid = false;
        this.pullSourceIdentityHash = 0;
    }

    /**
     * Invalidates all cached push and pull state (invoked on hopper facing change, removal, or
     * chunk unload per {@code S-002}).
     */
    public void invalidateAll() {
        invalidatePush();
        invalidatePull();
    }
}
