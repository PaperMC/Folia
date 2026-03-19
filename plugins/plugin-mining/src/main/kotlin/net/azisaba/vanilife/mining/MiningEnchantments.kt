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
    val AUTO_SMELT: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "auto_smelt"))
    val RANGE_MINING_3X3X3: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "range_mining_3x3x3"))
    val RANGE_MINING_5X5X5: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "range_mining_5x5x5"))
    val VEIN_MINING: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "vein_mining"))
    val VERTICAL_MINING_DOWN_1: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "vertical_mining_down_1"))
    val VERTICAL_MINING_UP_1: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "vertical_mining_up_1"))
    val VERTICAL_MINING_UP_DOWN_1: TypedKey<Enchantment> = RegistryKey.ENCHANTMENT.typedKey(Key.key(Vanilife.NAMESPACE, "vertical_mining_up_down_1"))

    private val MINER_EXCLUSIVES: RegistryKeySet<Enchantment> = RegistrySet.keySet(
        RegistryKey.ENCHANTMENT,
        RANGE_MINING_3X3X3,
        RANGE_MINING_5X5X5,
        VEIN_MINING,
        VERTICAL_MINING_DOWN_1,
        VERTICAL_MINING_UP_1,
        VERTICAL_MINING_UP_DOWN_1,
    )

    fun bootstrap(event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>) {
        event.registry().register(AUTO_SMELT) { builder -> autoSmelt(event, builder) }
        event.registry().register(RANGE_MINING_3X3X3) { builder -> rangeMining3x3x3(event, builder) }
        event.registry().register(RANGE_MINING_5X5X5) { builder -> rangeMining5x5x5(event, builder) }
        event.registry().register(VEIN_MINING) { builder -> veinMining16(event, builder) }
        event.registry().register(VERTICAL_MINING_UP_1) { builder -> verticalMiningUp1(event, builder) }
        event.registry().register(VERTICAL_MINING_DOWN_1) { builder -> verticalMiningDown1(event, builder) }
        event.registry().register(VERTICAL_MINING_UP_DOWN_1) { builder -> verticalMiningUpDown1(event, builder) }
    }

    private fun autoSmelt(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_AUTO_SMELT))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
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
            .exclusiveWith(MINER_EXCLUSIVES)
    }

    private fun rangeMining5x5x5(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_RANGE_MINING_5X5X5))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(MINER_EXCLUSIVES)
    }

    private fun veinMining16(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_VEIN_MINING))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(MINER_EXCLUSIVES)
    }

    private fun verticalMiningDown1(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_VERTICAL_MINING_DOWN_1))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(MINER_EXCLUSIVES)
    }

    private fun verticalMiningUp1(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_1))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(MINER_EXCLUSIVES)
    }

    private fun verticalMiningUpDown1(
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        builder: EnchantmentRegistryEntry.Builder,
    ) {
        builder.description(Component.translatable(MiningTranslations.ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_DOWN_1))
            .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
            .weight(1)
            .maxLevel(1)
            .anvilCost(1)
            .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 1))
            .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(3, 1))
            .activeSlots(EquipmentSlotGroup.ANY)
            .exclusiveWith(MINER_EXCLUSIVES)
    }
}
