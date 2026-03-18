package net.azisaba.vanilife.toolswap

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.EnchantmentRegistryEntry
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup

object ToolSwapEnchantments {
    val TOOL_SWAP: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "tool_swap"))

    internal fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(TOOL_SWAP) { builder -> toolSwap(event, builder) }
    }

    private fun toolSwap(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(ToolSwapTranslations.ENCHANTMENT_VANILIFE_TOOL_SWAP))
            .supportedItems(event.getOrCreateTag(ToolSwapItemTypeTags.ENCHANTABLE_TOOL_SWAP))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }
}
