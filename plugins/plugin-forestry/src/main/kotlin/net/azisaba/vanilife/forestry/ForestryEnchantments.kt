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
    val ACACIA_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "acacia_timber"))
    val AUTO_SAPLING: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "auto_sapling"))
    val BIRCH_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "birch_timer"))
    val DARK_OAK_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "dark_oak_timer"))
    val JUNGLE_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "jungle_timer"))
    val OAK_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "oak_timber"))
    val PALE_OAK_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "pale_oak_timber"))
    val SPRUCE_TIMBER: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "spruce_timer"))

    internal fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(ACACIA_TIMBER) { acaciaTimber(event, it) }
        event.registry().register(AUTO_SAPLING) { autoSapling(event, it) }
        event.registry().register(BIRCH_TIMBER) { birchTimber(event, it) }
        event.registry().register(DARK_OAK_TIMBER) { darkOakTimber(event, it) }
        event.registry().register(JUNGLE_TIMBER) { jungleTimber(event, it) }
        event.registry().register(OAK_TIMBER) { oakTimber(event, it) }
        event.registry().register(PALE_OAK_TIMBER) { paleOakTimber(event, it) }
        event.registry().register(SPRUCE_TIMBER) { spruceTimber(event, it) }
    }

    private fun acaciaTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_ACACIA_TIMBER))
    }

    private fun autoSapling(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_AUTO_SAPLING))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.AXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }

    private fun birchTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_BIRCH_TIMBER))
    }

    private fun darkOakTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_DARK_OAK_TIMBER))
    }

    private fun jungleTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_JUNGLE_TIMBER))
    }

    private fun oakTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_OAK_TIMBER))
    }

    private fun paleOakTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_PALE_OAK_TIMBER))
    }

    private fun spruceTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseTimber(event, builder)
        builder.description(Component.translatable(ForestryTranslations.ENCHANTMENT_VANILIFE_SPRUCE_TIMBER))
    }

    private fun baseTimber(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.supportedItems(event.getOrCreateTag(ItemTypeTagKeys.AXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }
}
