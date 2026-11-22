package dev.leafcraft.goldleaf.config;

import ca.spottedleaf.concurrentutil.numa.OSNuma;

/**
 * Immutable snapshot of the host hardware envelope probed at server startup.
 * Used to drive mechanics-neutral auto-tuning (such as Folia tick-thread allocation)
 * across surplus 4–8 core workstations through multi-socket server nodes.
 *
 * @param logicalCores normalized logical CPU core count ({@code >= 1})
 * @param maxHeapBytes JVM maximum heap size in bytes ({@code >= 0})
 * @param tier         coarse hardware tier classification
 */
public record HardwareEnvelope(
    int logicalCores,
    long maxHeapBytes,
    HardwareTier tier
) {

    public enum HardwareTier {
        WORKSTATION,
        SERVER,
        MEGA_NODE
    }

    public static HardwareEnvelope detect() {
        final int detectedCores = OSNuma.getNativeInstance().getTotalCores();
        final long maxHeap = Runtime.getRuntime().maxMemory();
        return of(detectedCores, maxHeap);
    }

    public static HardwareEnvelope of(final int logicalCores, final long maxHeapBytes) {
        final int normalizedCores = Math.max(1, logicalCores);
        final long normalizedHeap = Math.max(0L, maxHeapBytes);
        return new HardwareEnvelope(normalizedCores, normalizedHeap, classify(normalizedCores));
    }

    public static HardwareTier classify(final int logicalCores) {
        if (logicalCores <= 16) {
            return HardwareTier.WORKSTATION;
        }
        if (logicalCores <= 64) {
            return HardwareTier.SERVER;
        }
        return HardwareTier.MEGA_NODE;
    }

    public long maxHeapMiB() {
        return this.maxHeapBytes / (1024L * 1024L);
    }

    public String describe() {
        return "tier=" + this.tier
            + ", logicalCores=" + this.logicalCores
            + ", maxHeapMiB=" + this.maxHeapMiB();
    }
}
