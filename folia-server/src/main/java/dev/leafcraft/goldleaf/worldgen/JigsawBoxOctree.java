package dev.leafcraft.goldleaf.worldgen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Spatial AABB octree replacing {@code Shapes.joinIsNotEmpty(VoxelShape, VoxelShape, BooleanOp.ONLY_SECOND)}
 * inside {@code JigsawPlacement} ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}).
 *
 * <p>Tracks an outer placement boundary {@link AABB} minus a set of occupied piece {@link AABB}s.
 * A deflated candidate box fits inside the remaining free volume if and only if it lies completely
 * within {@code boundary} and does not strictly intersect any occupied box (matching Minecraft's
 * {@code 1.0E-7} {@code Shapes.EPSILON} tolerance).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-013, goldleaf-ADR-017
 * @trace S-001, S-004
 */
public final class JigsawBoxOctree {

    private static final double EPSILON = 1.0E-7;
    private static final int SPLIT_THRESHOLD = 8;
    private static final int MAX_DEPTH = 6;

    private final AABB boundary;
    private final Node root;

    public JigsawBoxOctree(final AABB boundary) {
        this.boundary = boundary;
        this.root = new Node(
            boundary.minX, boundary.minY, boundary.minZ,
            boundary.maxX, boundary.maxY, boundary.maxZ,
            0
        );
    }

    /**
     * Carves an occupied piece box out of the free volume.
     */
    public void addBox(final AABB box) {
        if (!strictlyIntersects(
            this.boundary.minX, this.boundary.minY, this.boundary.minZ,
            this.boundary.maxX, this.boundary.maxY, this.boundary.maxZ,
            box.minX, box.minY, box.minZ,
            box.maxX, box.maxY, box.maxZ
        )) {
            return;
        }
        this.root.add(box);
    }

    /**
     * Returns {@code true} iff {@code candidate} is completely contained inside the outer boundary
     * and does not strictly intersect any previously added occupied box.
     */
    public boolean fitsInside(final AABB candidate) {
        if (candidate.minX >= candidate.maxX - EPSILON
            || candidate.minY >= candidate.maxY - EPSILON
            || candidate.minZ >= candidate.maxZ - EPSILON) {
            return true;
        }
        if (candidate.minX < this.boundary.minX - EPSILON
            || candidate.maxX > this.boundary.maxX + EPSILON
            || candidate.minY < this.boundary.minY - EPSILON
            || candidate.maxY > this.boundary.maxY + EPSILON
            || candidate.minZ < this.boundary.minZ - EPSILON
            || candidate.maxZ > this.boundary.maxZ + EPSILON) {
            return false;
        }
        return !this.root.intersectsAny(candidate);
    }

    private static boolean strictlyIntersects(
        final double aMinX, final double aMinY, final double aMinZ,
        final double aMaxX, final double aMaxY, final double aMaxZ,
        final double bMinX, final double bMinY, final double bMinZ,
        final double bMaxX, final double bMaxY, final double bMaxZ
    ) {
        return aMinX + EPSILON < bMaxX
            && aMaxX - EPSILON > bMinX
            && aMinY + EPSILON < bMaxY
            && aMaxY - EPSILON > bMinY
            && aMinZ + EPSILON < bMaxZ
            && aMaxZ - EPSILON > bMinZ;
    }

    private static final class Node {
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;
        private final double midX;
        private final double midY;
        private final double midZ;
        private final int depth;
        private final List<AABB> boxes = new ArrayList<>(SPLIT_THRESHOLD);
        private Node @Nullable [] children;

        private Node(
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ,
            final int depth
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.midX = 0.5 * (minX + maxX);
            this.midY = 0.5 * (minY + maxY);
            this.midZ = 0.5 * (minZ + maxZ);
            this.depth = depth;
        }

        private void add(final AABB box) {
            if (this.children == null) {
                this.boxes.add(box);
                if (this.boxes.size() > SPLIT_THRESHOLD && this.depth < MAX_DEPTH) {
                    this.split();
                }
                return;
            }
            this.insertIntoChildren(box);
        }

        private void split() {
            final Node[] kids = new Node[8];
            final int nextDepth = this.depth + 1;
            kids[0] = new Node(this.minX, this.minY, this.minZ, this.midX, this.midY, this.midZ, nextDepth);
            kids[1] = new Node(this.midX, this.minY, this.minZ, this.maxX, this.midY, this.midZ, nextDepth);
            kids[2] = new Node(this.minX, this.midY, this.minZ, this.midX, this.maxY, this.midZ, nextDepth);
            kids[3] = new Node(this.midX, this.midY, this.minZ, this.maxX, this.maxY, this.midZ, nextDepth);
            kids[4] = new Node(this.minX, this.minY, this.midZ, this.midX, this.midY, this.maxZ, nextDepth);
            kids[5] = new Node(this.midX, this.minY, this.midZ, this.maxX, this.midY, this.maxZ, nextDepth);
            kids[6] = new Node(this.minX, this.midY, this.midZ, this.midX, this.maxY, this.maxZ, nextDepth);
            kids[7] = new Node(this.midX, this.midY, this.midZ, this.maxX, this.maxY, this.maxZ, nextDepth);
            this.children = kids;

            final int count = this.boxes.size();
            for (int i = 0; i < count; i++) {
                this.insertIntoChildren(this.boxes.get(i));
            }
            this.boxes.clear();
        }

        private void insertIntoChildren(final AABB box) {
            final Node[] kids = this.children;
            if (kids == null) {
                return;
            }
            final boolean loX = box.minX + EPSILON < this.midX;
            final boolean hiX = box.maxX - EPSILON > this.midX;
            final boolean loY = box.minY + EPSILON < this.midY;
            final boolean hiY = box.maxY - EPSILON > this.midY;
            final boolean loZ = box.minZ + EPSILON < this.midZ;
            final boolean hiZ = box.maxZ - EPSILON > this.midZ;
            if (loZ) {
                if (loY) {
                    if (loX) kids[0].add(box);
                    if (hiX) kids[1].add(box);
                }
                if (hiY) {
                    if (loX) kids[2].add(box);
                    if (hiX) kids[3].add(box);
                }
            }
            if (hiZ) {
                if (loY) {
                    if (loX) kids[4].add(box);
                    if (hiX) kids[5].add(box);
                }
                if (hiY) {
                    if (loX) kids[6].add(box);
                    if (hiX) kids[7].add(box);
                }
            }
        }

        private boolean intersectsAny(final AABB candidate) {
            final Node[] kids = this.children;
            if (kids == null) {
                final int count = this.boxes.size();
                for (int i = 0; i < count; i++) {
                    final AABB existing = this.boxes.get(i);
                    if (strictlyIntersects(
                        candidate.minX, candidate.minY, candidate.minZ,
                        candidate.maxX, candidate.maxY, candidate.maxZ,
                        existing.minX, existing.minY, existing.minZ,
                        existing.maxX, existing.maxY, existing.maxZ
                    )) {
                        return true;
                    }
                }
                return false;
            }
            final boolean loX = candidate.minX + EPSILON < this.midX;
            final boolean hiX = candidate.maxX - EPSILON > this.midX;
            final boolean loY = candidate.minY + EPSILON < this.midY;
            final boolean hiY = candidate.maxY - EPSILON > this.midY;
            final boolean loZ = candidate.minZ + EPSILON < this.midZ;
            final boolean hiZ = candidate.maxZ - EPSILON > this.midZ;
            if (loZ) {
                if (loY) {
                    if (loX && kids[0].intersectsAny(candidate)) return true;
                    if (hiX && kids[1].intersectsAny(candidate)) return true;
                }
                if (hiY) {
                    if (loX && kids[2].intersectsAny(candidate)) return true;
                    if (hiX && kids[3].intersectsAny(candidate)) return true;
                }
            }
            if (hiZ) {
                if (loY) {
                    if (loX && kids[4].intersectsAny(candidate)) return true;
                    if (hiX && kids[5].intersectsAny(candidate)) return true;
                }
                if (hiY) {
                    if (loX && kids[6].intersectsAny(candidate)) return true;
                    if (hiX && kids[7].intersectsAny(candidate)) return true;
                }
            }
            return false;
        }
    }
}
