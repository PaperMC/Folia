package net.azisaba.vanilife.npc

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.forestry.ForestryItems
import net.azisaba.vanilife.item.ServerItem
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import kotlin.random.Random

@ConsistentCopyVisibility
data class UnreadableRecipe private constructor(
    val item: TypedKey<ServerItem>,
    val rarity: Int,
    private val resultProvider: () -> ItemStack,
    private val costProvider: (Random) -> ItemStack,
) : Recipe {
    override fun getResult(): ItemStack = resultProvider()

    fun rollCost(random: Random) = costProvider(random)

    fun rollExperienceCost(random: Random): Int = minOf(rarity * 5 + random.nextInt(5, 11), 64)

    companion object {
        private val SET: MutableSet<UnreadableRecipe> = mutableSetOf()
        private val BY_RECIPE_ITEM: MutableMap<TypedKey<ServerItem>, UnreadableRecipe> = mutableMapOf()

        val CUT_ALL: UnreadableRecipe =
            enchantment(NpcItems.CUT_ALL_RECIPE, 3, ForestryEnchantments.OAK_TIMBER) { random ->
                ItemStack.of(ForestryItems.SMALL_TREE_STUMP, (15..32).random(random))
            }

        fun byItem(recipeItem: ServerItem): UnreadableRecipe? =
            byItem(RegistryKey.SERVER_ITEM.typedKey(recipeItem.key()))

        fun byItem(recipeItem: TypedKey<ServerItem>): UnreadableRecipe? = BY_RECIPE_ITEM[recipeItem]

        private fun item(
            item: TypedKey<ServerItem>,
            rarity: Int,
            result: TypedKey<ServerItem>,
            cost: (Random) -> ItemStack,
        ): UnreadableRecipe = register(UnreadableRecipe(item, rarity, { ItemStack.of(result) }, cost))

        private fun material(
            item: TypedKey<ServerItem>,
            rarity: Int,
            result: Material,
            cost: (Random) -> ItemStack,
        ): UnreadableRecipe = register(UnreadableRecipe(item, rarity, { ItemStack.of(result) }, cost))

        private fun enchantment(
            item: TypedKey<ServerItem>,
            rarity: Int,
            result: TypedKey<Enchantment>,
            level: Int = 1,
            cost: (Random) -> ItemStack,
        ): UnreadableRecipe = register(
            UnreadableRecipe(
                item,
                rarity,
                {
                    ItemStack.of(Material.ENCHANTED_BOOK).apply {
                        editMeta(EnchantmentStorageMeta::class.java) { meta ->
                            val enchantment = RegistryAccess.registryAccess()
                                .getRegistry(RegistryKey.ENCHANTMENT)
                                .getOrThrow(result)
                            meta.addStoredEnchant(enchantment, level, true)
                        }
                    }
                },
                cost
            )
        )

        private fun register(value: UnreadableRecipe): UnreadableRecipe {
            SET.add(value)
            BY_RECIPE_ITEM[value.item] = value
            return value
        }
    }
}