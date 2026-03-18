package net.azisaba.vanilife.farming

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

object FarmingEnchantments {
    val BEETROOT_AUTO_REPLANT: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "beetroot_auto_replant"))
    val CARROT_AUTO_REPLANT: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "carrot_auto_replant"))
    val POTATO_AUTO_REPLANT: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "potato_auto_replant"))
    val WHEAT_AUTO_REPLANT: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "wheat_auto_replant"))

    internal fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(BEETROOT_AUTO_REPLANT) { builder -> beetrootAutoReplant(event, builder) }
        event.registry().register(CARROT_AUTO_REPLANT) { builder -> carrotAutoReplant(event, builder) }
        event.registry().register(POTATO_AUTO_REPLANT) { builder -> potatoAutoReplant(event, builder) }
        event.registry().register(WHEAT_AUTO_REPLANT) { builder -> wheatAutoReplant(event, builder) }
    }

    private fun beetrootAutoReplant(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseAutoReplant(event, builder)
        builder.description(Component.translatable(FarmingTranslations.ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT))
    }

    private fun carrotAutoReplant(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseAutoReplant(event, builder)
        builder.description(Component.translatable(FarmingTranslations.ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT))
    }

    private fun potatoAutoReplant(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseAutoReplant(event, builder)
        builder.description(Component.translatable(FarmingTranslations.ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT))
    }

    private fun wheatAutoReplant(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        baseAutoReplant(event, builder)
        builder.description(Component.translatable(FarmingTranslations.ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT))
    }

    private fun baseAutoReplant(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.supportedItems(event.getOrCreateTag(ItemTypeTagKeys.HOES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
    }
}
