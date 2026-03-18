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

object ForestryEnchantments {
    val AUTO_SAPLING: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "auto_sapling"))
    val TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "timber"))

    internal fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(AUTO_SAPLING) { autoSapling(event, it) }
        event.registry().register(TIMBER) { timber(event, it) }
    }

    private fun autoSapling(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>, builder: EnchantmentRegistryEntry.Builder) {
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_AUTO_SAPLING))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.AXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }

    private fun timber(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>, builder: EnchantmentRegistryEntry.Builder) {
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_TIMBER))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.AXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }
}
