package net.azisaba.vanilife.event;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BlockDropLootEvent extends BlockEvent {
    private static final @NotNull HandlerList HANDLER_LIST = new HandlerList();

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    private final @NotNull BlockState blockState;

    private final @Nullable Entity entity;
    private final @Nullable ItemStack tool;

    private final @NotNull List<ItemStack> drops;

    @ApiStatus.Internal
    public BlockDropLootEvent(
            final @NotNull Block block,
            final @NotNull BlockState blockState,
            final @Nullable Entity entity,
            final @Nullable ItemStack tool,
            final @NotNull List<ItemStack> drops
    ) {
        super(block);
        this.blockState = blockState;
        this.entity = entity;
        this.tool = tool;
        this.drops = drops;
    }

    public @NotNull BlockState getBlockState() {
        return this.blockState;
    }

    public @Nullable Entity getEntity() {
        return this.entity;
    }

    public @Nullable ItemStack getTool() {
        return this.tool;
    }

    public @NotNull List<ItemStack> getDrops() {
        return this.drops.stream().map(ItemStack::clone).toList();
    }

    public void setDrops(final @NotNull List<ItemStack> drops) {
        this.drops.clear();
        this.drops.addAll(drops);
    }

    public void mapDrops(final @NotNull UnaryOperator<ItemStack> mapper) {
        this.setDrops(this.getDrops().stream().map(mapper).toList());
    }

    public void filterDrops(final @NotNull Predicate<ItemStack> predicate) {
        this.setDrops(this.getDrops().stream().filter(predicate).toList());
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }
}
