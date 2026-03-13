package net.azisaba.vanilife.npc.trading

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.item.ServerItemType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

sealed interface UnquantifiedItemStack {
    fun resolve(random: Random): ItemStack

    companion object {
        fun of(type: Material, quantity: QuantityProvider): UnquantifiedItemStack = M(type, quantity)

        fun of(type: ServerItemType, quantity: QuantityProvider): UnquantifiedItemStack = S(type, quantity)

        fun of(type: TypedKey<ServerItemType>, quantity: QuantityProvider): UnquantifiedItemStack {
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SERVER_ITEM)
            val unwrappedType = registry.getOrThrow(type)
            return of(unwrappedType, quantity)
        }
    }

    private data class M(val type: Material, val quantity: QuantityProvider) : UnquantifiedItemStack {
        override fun resolve(random: Random): ItemStack = ItemStack.of(type, quantity.provide(random))
    }

    private data class S(val type: ServerItemType, val quantity: QuantityProvider) : UnquantifiedItemStack {
        override fun resolve(random: Random): ItemStack = ItemStack.of(type, quantity.provide(random))
    }
}
