package net.azisaba.vanilife.npc.recipe

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.forestry.ForestryItems
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.npc.Npc
import net.azisaba.vanilife.npc.NpcItems
import net.azisaba.vanilife.npc.NpcType
import net.kyori.adventure.audience.Audience
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.meta.EnchantmentStorageMeta

@ConsistentCopyVisibility
data class UnreadableRecipe private constructor(
    val item: TypedKey<ServerItem>,
    val reader: NpcType,
    val experienceCost: Int,
    private val resultItemProvider: () -> ItemStack,
    private val costItemProvider: () -> ItemStack,
) {
    constructor(
        item: TypedKey<ServerItem>,
        reader: NpcType,
        resultItem: Pair<TypedKey<ServerItem>, Int>,
        costItem: Pair<TypedKey<ServerItem>, Int>,
        experienceCost: Int,
    ) : this(
        item,
        reader,
        experienceCost,
        { ItemStack.of(resultItem.first, resultItem.second) },
        { ItemStack.of(costItem.first, costItem.second) },
    )

    constructor(
        item: TypedKey<ServerItem>,
        reader: NpcType,
        resultEnchantment: TypedKey<Enchantment>,
        resultEnchantmentLevel: Int,
        costItem: Pair<TypedKey<ServerItem>, Int>,
        experienceCost: Int,
    ) : this(
        item,
        reader,
        experienceCost,
        {
            ItemStack.of(Material.ENCHANTED_BOOK).apply {
                editMeta(EnchantmentStorageMeta::class.java) { meta ->
                    val resolvedEnchantment = RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.ENCHANTMENT)
                        .getOrThrow(resultEnchantment)
                    meta.addStoredEnchant(resolvedEnchantment, resultEnchantmentLevel, true)
                }
            }
        },
        { ItemStack.of(costItem.first, costItem.second) },
    )

    fun canReadBy(audience: Audience): Boolean = audience is Npc && audience.npcType == reader

    fun createResultItem(): ItemStack = resultItemProvider()

    fun createCostItem(): ItemStack = costItemProvider()

    fun asItem(): ServerItem = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.SERVER_ITEM)
        .getOrThrow(item)

    fun toMerchantRecipe(): MerchantRecipe = MerchantRecipe(createResultItem(), 15).apply {
        addIngredient(createCostItem())
        addIngredient(ItemStack.of(NpcItems.EXPERIENCE, experienceCost))
    }

    companion object {
        private val SET: MutableSet<UnreadableRecipe> = mutableSetOf()
        private val BY_RECIPE_ITEM: MutableMap<TypedKey<ServerItem>, UnreadableRecipe> = mutableMapOf()

        val CUT_ALL: UnreadableRecipe = register(
            UnreadableRecipe(
                item = NpcItems.CUT_ALL_RECIPE,
                reader = NpcType.NEKO,
                resultEnchantment = ForestryEnchantments.CUT_ALL,
                resultEnchantmentLevel = 1,
                costItem = ForestryItems.SMALL_TREE_STUMP to 16,
                experienceCost = 6,
            )
        )

        fun all(): Set<UnreadableRecipe> = SET.toSet()

        fun byRecipeItem(recipeItem: ServerItem): UnreadableRecipe? =
            byRecipeItem(RegistryKey.SERVER_ITEM.typedKey(recipeItem.key()))

        fun byRecipeItem(recipeItem: TypedKey<ServerItem>): UnreadableRecipe? = BY_RECIPE_ITEM[recipeItem]

        private fun register(unreadableRecipe: UnreadableRecipe): UnreadableRecipe {
            SET.add(unreadableRecipe)
            BY_RECIPE_ITEM[unreadableRecipe.item] = unreadableRecipe
            return unreadableRecipe
        }
    }
}
