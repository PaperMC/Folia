package io.papermc.paper.spi;

/**
 * Lazily allocated per-{@code LevelChunk} primitive activity counter sidecar
 * ({@code goldleaf-ADR-006} §1.2).
 *
 * <p>Incremented inline on the owning region thread with zero boxing and zero Bukkit event
 * allocation. Reset and cleared on chunk unload ({@code S-002}).</p>
 */
public final class ChunkActivityCounters {

    private int redstoneUpdates;
    private int hopperTransfers;
    private int pistonMoves;
    private int blockGrowths;
    private int entityTicks;

    public record Snapshot(
        int redstoneUpdates,
        int hopperTransfers,
        int pistonMoves,
        int blockGrowths,
        int entityTicks
    ) {
        public boolean isEmpty() {
            return this.redstoneUpdates == 0
                && this.hopperTransfers == 0
                && this.pistonMoves == 0
                && this.blockGrowths == 0
                && this.entityTicks == 0;
        }
    }

    public void recordRedstoneUpdate() {
        this.redstoneUpdates++;
    }

    public void recordHopperTransfer() {
        this.hopperTransfers++;
    }

    public void recordPistonMove() {
        this.pistonMoves++;
    }

    public void recordBlockGrowth() {
        this.blockGrowths++;
    }

    public void recordEntityTick() {
        this.entityTicks++;
    }

    public int redstoneUpdates() {
        return this.redstoneUpdates;
    }

    public int hopperTransfers() {
        return this.hopperTransfers;
    }

    public int pistonMoves() {
        return this.pistonMoves;
    }

    public int blockGrowths() {
        return this.blockGrowths;
    }

    public int entityTicks() {
        return this.entityTicks;
    }

    /**
     * Captures an immutable snapshot and zeroes all primitive counters in-place on the owning
     * region thread.
     */
    public Snapshot drainSnapshot() {
        final Snapshot snap = new Snapshot(
            this.redstoneUpdates,
            this.hopperTransfers,
            this.pistonMoves,
            this.blockGrowths,
            this.entityTicks
        );
        this.reset();
        return snap;
    }

    public void reset() {
        this.redstoneUpdates = 0;
        this.hopperTransfers = 0;
        this.pistonMoves = 0;
        this.blockGrowths = 0;
        this.entityTicks = 0;
    }
}
