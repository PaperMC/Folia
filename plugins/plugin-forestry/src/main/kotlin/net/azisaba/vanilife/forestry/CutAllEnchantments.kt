package net.azisaba.vanilife.forestry

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.EnchantmentRegistryEntry
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup

object CutAllEnchantments {
    val CUT_ALL: TypedKey<Enchantment> = TypedKey.create(RegistryKey.ENCHANTMENT, Key.key(Vanilife.NAMESPACE, "cut_all"))

    fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(CUT_ALL) { cutAll(event, it) }
    }

    private fun cutAll(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>, builder: EnchantmentRegistryEntry.Builder) {
        builder.description(Component.text("一括伐採"))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.AXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }
}
