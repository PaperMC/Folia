package net.azisaba.vanilife.npc.recipe

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.npc.*
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemLoreStyle
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta

sealed interface UnreadableRecipe {
    val recipeItem: TypedKey<net.azisaba.vanilife.item.ServerItem>

    val reader: NpcType

    fun canRead(audience: Audience): Boolean = audience is Npc && audience.npcType == reader

    fun createResultItem(): ItemStack

    fun recipeItem(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(NpcTranslations.ITEM_VANILIFE_UNREADABLE_RECIPE)
            .describe()
            .category(ServerItemCategory.MATERIAL)
            .loreStyle(
                ServerItemLoreStyle.loreStyle()
                    .then(ServerItemRegistryEntry::described, ServerItemLoreStyle.Part.description())
                    .then(ServerItemLoreStyle.Part.itemCategory())
                    .then { _, builder ->
                        builder.add(Component.translatable(NpcTranslations.ITEM_VANILIFE_UNREADABLE_RECIPE_READABLE_NPC_TYPES))
                            .add(Component.text(reader.icon).font(NpcFonts.NPC_ICONS))
                    }
                    .build()
            )
            .itemModel(NpcItemModels.UNREADABLE_RECIPE)
    }

    companion object {
        private val SET: MutableSet<UnreadableRecipe> = mutableSetOf()
        private val BY_RECIPE_ITEM: MutableMap<TypedKey<net.azisaba.vanilife.item.ServerItem>, UnreadableRecipe> =
            mutableMapOf()

        val CUT_ALL: Enchantment = register(
            Enchantment(
                enchantment = ForestryEnchantments.CUT_ALL,
                enchantmentLevel = 1,
                recipeItem = NpcItems.CUT_ALL_UNREADABLE_RECIPE,
                reader = NpcType.NEKO,
            )
        )

        fun all(): Set<UnreadableRecipe> = SET.toSet()

        fun byRecipeItem(recipeItem: net.azisaba.vanilife.item.ServerItem): UnreadableRecipe? =
            byRecipeItem(RegistryKey.SERVER_ITEM.typedKey(recipeItem.key()))

        fun byRecipeItem(recipeItem: TypedKey<net.azisaba.vanilife.item.ServerItem>): UnreadableRecipe? =
            BY_RECIPE_ITEM[recipeItem]

        private fun <T : UnreadableRecipe> register(unreadableRecipe: T): T {
            SET.add(unreadableRecipe)
            BY_RECIPE_ITEM[unreadableRecipe.recipeItem] = unreadableRecipe
            return unreadableRecipe
        }
    }

    @ConsistentCopyVisibility
    data class ServerItem internal constructor(
        val resultItem: TypedKey<net.azisaba.vanilife.item.ServerItem>,
        override val recipeItem: TypedKey<net.azisaba.vanilife.item.ServerItem>,
        override val reader: NpcType,
    ) : UnreadableRecipe {
        override fun createResultItem(): ItemStack = ItemStack.of(resultItem)
    }

    @ConsistentCopyVisibility
    data class Enchantment internal constructor(
        val enchantment: TypedKey<org.bukkit.enchantments.Enchantment>,
        val enchantmentLevel: Int,
        override val recipeItem: TypedKey<net.azisaba.vanilife.item.ServerItem>,
        override val reader: NpcType,
    ) : UnreadableRecipe {
        override fun createResultItem(): ItemStack = ItemStack.of(Material.ENCHANTED_BOOK).apply {
            editMeta(EnchantmentStorageMeta::class.java) { meta ->
                val resolvedEnchantment = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT)
                    .getOrThrow(enchantment)
                meta.addStoredEnchant(resolvedEnchantment, enchantmentLevel, true)
            }
        }
    }
}
