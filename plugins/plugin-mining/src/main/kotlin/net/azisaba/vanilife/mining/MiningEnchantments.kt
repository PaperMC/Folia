package net.azisaba.vanilife.mining

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.EnchantmentRegistryEntry
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys
import io.papermc.paper.registry.set.RegistryKeySet
import io.papermc.paper.registry.set.RegistrySet
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup

object MiningEnchantments {
    val RANGE_MINING_3X3X3: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "range_mining_3x3x3"))
    val RANGE_MINING_5X5X5: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "range_mining_5x5x5"))

    fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(RANGE_MINING_3X3X3) { builder -> rangeMining3x3x3(event, builder) }
        event.registry().register(RANGE_MINING_5X5X5) { builder -> rangeMining5x5x5(event, builder) }
    }

    private fun rangeMining3x3x3(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_RANGE_MINING_3X3X3))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT, RANGE_MINING_5X5X5))
    }

    private fun rangeMining5x5x5(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_RANGE_MINING_3X3X3))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT, RANGE_MINING_3X3X3))
    }
}
