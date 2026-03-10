package net.azisaba.vanilife.cooking

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.model
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object CookingItemModels {
    val BAMBOO_SHOOT: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/bamboo_shoot")
    val CLAM: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/clam")
    val FIREFLY_SQUID: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/firefly_squid")
    val SKIPJACK_TUNA: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/skipjack_tuna")
    val SPANISH_MACKEREL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/spanish_mackerel")
    val TOMATO_MODEL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/tomato")

    val BAMBOO_SHOOT_ITEM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "bamboo_shoot")
    val CLAM_ITEM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "clam")
    val FIREFLY_SQUID_ITEM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "firefly_squid")
    val SKIPJACK_TUNA_ITEM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "skipjack_tuna")
    val SPANISH_MACKEREL_ITEM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "spanish_mackerel")
    val TOMATO: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "tomato")

    fun bambooShoot(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/bamboo_shoot"))

    fun clam(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/clam"))

    fun fireflySquid(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/firefly_squid"))

    fun skipjackTuna(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/skipjack_tuna"))

    fun spanishMackerel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/spanish_mackerel"))

    fun tomatoModel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/tomato"))

    fun bambooShootItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(BAMBOO_SHOOT))

    fun clamItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(CLAM))

    fun fireflySquidItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FIREFLY_SQUID))

    fun skipjackTunaItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(SKIPJACK_TUNA))

    fun spanishMackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(SPANISH_MACKEREL))

    fun tomato(): PackItemModel = PackItemModel(PackModelItemModelProperties(TOMATO_MODEL))
}
