package net.azisaba.vanilife.registry.data;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.*;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.registry.RegistryBuilder;
import io.papermc.paper.registry.keys.SoundEventKeys;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItemType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.JukeboxSong;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface ServerItemTypeRegistryEntry {
    @Contract(pure = true)
    Component displayName();

    @Contract(pure = true)
    @Nullable Component flavorText();

    @Contract(pure = true)
    Set<Season.Sub> peakSeason();

    @Contract(pure = true)
    <T> @Nullable T component(final DataComponentType.Valued<T> type);

    @Contract(pure = true)
    boolean hasComponent(final DataComponentType type);

    void applyComponents(final ItemStack itemStack);

    @ApiStatus.Experimental
    @ApiStatus.NonExtendable
    interface Builder extends RegistryBuilder<ServerItemType> {
        @Contract(value = "_ -> this", mutates = "this")
        Builder displayName(final Component displayName);

        @Contract(value = "_ -> this", mutates = "this")
        Builder flavorText(final Component flavorText);

        @Contract(value = "_ -> this", mutates = "this")
        Builder peakSeason(final Season.Sub... subSeasons);

        @Contract(value = "_, _ -> this", mutates = "this")
        <T> Builder withComponent(final DataComponentType.Valued<T> type, T value);

        @Contract(value = "_ -> this", mutates = "this")
        Builder withComponent(final DataComponentType.NonValued type);

        @Contract(value = "_ -> this", mutates = "this")
        default Builder itemModel(final Key itemModel) {
            return this.withComponent(DataComponentTypes.ITEM_MODEL, itemModel);
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder food(final FoodProperties food) {
            return this.food(
                    food,
                    Consumable.consumable()
                            .consumeSeconds(1.6F)
                            .animation(ItemUseAnimation.EAT)
                            .sound(SoundEventKeys.ENTITY_GENERIC_EAT)
                            .hasConsumeParticles(true)
                            .build()
            );
        }

        @Contract(value = "_, _ -> this", mutates = "this")
        default Builder food(final FoodProperties food, final Consumable consumable) {
            return this.withComponent(DataComponentTypes.FOOD, food).withComponent(DataComponentTypes.CONSUMABLE, consumable);
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder usingConvertsTo(final ItemStack usingConvertsTo) {
            return this.withComponent(DataComponentTypes.USE_REMAINDER, UseRemainder.useRemainder(usingConvertsTo));
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder useCooldown(final float useCooldown) {
            return this.withComponent(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(useCooldown).build());
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder stacksTo(final int maxStackSize) {
            return this.withComponent(DataComponentTypes.MAX_STACK_SIZE, maxStackSize);
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder durability(final int maxDamage) {
            this.withComponent(DataComponentTypes.MAX_DAMAGE, maxDamage);
            this.withComponent(DataComponentTypes.MAX_STACK_SIZE, 1);
            this.withComponent(DataComponentTypes.DAMAGE, 0);
            return this;
        }

        @Contract(value = "-> this", mutates = "this")
        default Builder fireResistant() {
            return this.withComponent(DataComponentTypes.DAMAGE_RESISTANT, DamageResistant.damageResistant(DamageTypeTagKeys.IS_FIRE));
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder jukeboxPlayable(final JukeboxSong song) {
            return this.withComponent(DataComponentTypes.JUKEBOX_PLAYABLE, JukeboxPlayable.jukeboxPlayable(song).build());
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder enchantable(final int enchantmentValue) {
            return this.withComponent(DataComponentTypes.ENCHANTABLE, Enchantable.enchantable(enchantmentValue));
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder equippable(final EquipmentSlot slot) {
            return this.withComponent(DataComponentTypes.EQUIPPABLE, Equippable.equippable(slot).build());
        }

        @Contract(value = "_ -> this", mutates = "this")
        default Builder equippableUnswappable(final EquipmentSlot slot) {
            return this.withComponent(DataComponentTypes.EQUIPPABLE, Equippable.equippable(slot).swappable(false).build());
        }
    }
}
