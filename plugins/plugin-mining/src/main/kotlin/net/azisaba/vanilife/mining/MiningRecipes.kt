package net.azisaba.vanilife.mining

import net.azisaba.vanilife.Vanilife
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CampfireRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice

object MiningRecipes {
    val COAL_FROM_BLASTING_FROZEN_COAL: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "coal_from_blasting_frozen_coal")
    val COAL_FROM_FROZEN_COAL_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "coal_from_frozen_coal_campfire_cooking")
    val COAL_FROM_SMELTING_FROZEN_COAL: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "coal_from_smelting_frozen_coal")
    val DIAMOND_FROM_BLASTING_FROZEN_DIAMOND: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "diamond_from_blasting_frozen_diamond")
    val DIAMOND_FROM_FROZEN_DIAMOND_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "diamond_from_frozen_diamond_campfire_cooking")
    val DIAMOND_FROM_SMELTING_FROZEN_DIAMOND: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "diamond_from_smelting_frozen_diamond")
    val EMERALD_FROM_BLASTING_FROZEN_EMERALD: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "emerald_from_blasting_frozen_emerald")
    val EMERALD_FROM_FROZEN_EMERALD_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "emerald_from_frozen_emerald_campfire_cooking")
    val EMERALD_FROM_SMELTING_FROZEN_EMERALD: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "emerald_from_smelting_frozen_emerald")
    val LAPIS_LAZULI_FROM_BLASTING_FROZEN_LAPIS_LAZULI: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "lapis_lazuli_from_blasting_frozen_lapis_lazuli")
    val LAPIS_LAZULI_FROM_FROZEN_LAPIS_LAZULI_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "lapis_lazuli_from_frozen_lapis_lazuli_campfire_cooking")
    val LAPIS_LAZULI_FROM_SMELTING_FROZEN_LAPIS_LAZULI: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "lapis_lazuli_from_smelting_frozen_lapis_lazuli")
    val RAW_COPPER_FROM_BLASTING_FROZEN_RAW_COPPER: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_copper_from_blasting_frozen_raw_copper")
    val RAW_COPPER_FROM_FROZEN_RAW_COPPER_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_copper_from_frozen_raw_copper_campfire_cooking")
    val RAW_COPPER_FROM_SMELTING_FROZEN_RAW_COPPER: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_copper_from_smelting_frozen_raw_copper")
    val RAW_GOLD_FROM_BASTING_FROZEN_RAW_GOLD: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_gold_from_blasting_frozen_raw_gold")
    val RAW_GOLD_FROM_FROZEN_RAW_GOLD_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_gold_from_frozen_raw_gold_campfire_cooking")
    val RAW_GOLD_FROM_SMELTING_FROZEN_RAW_GOLD: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_gold_from_smelting_frozen_raw_gold")
    val RAW_IRON_FROM_BLASTING_FROZEN_RAW_IRON: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_iron_from_blasting_frozen_raw_iron")
    val RAW_IRON_FROM_FROZEN_RAW_IRON_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_iron_from_frozen_raw_iron_campfire_cooking")
    val RAW_IRON_FROM_SMELTING_FROZEN_RAW_IRON: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "raw_iron_from_smelting_frozen_raw_iron")
    val REDSTONE_FROM_BLASTING_FROZEN_REDSTONE: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "redstone_from_blasting_frozen_redstone")
    val REDSTONE_FROM_FROZEN_REDSTONE_CAMPFIRE_COOKING: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "redstone_from_frozen_redstone_campfire_cooking")
    val REDSTONE_FROM_SMELTING_FROZEN_REDSTONE: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "redstone_from_smelting_frozen_redstone")

    fun bootstrap(server: Server) {
        server.addRecipe(coalFromBlastingFrozenCoal())
        server.addRecipe(coalFromFrozenCoalCampfireCooking())
        server.addRecipe(coalFromSmeltingFrozenCoal())

        server.addRecipe(diamondFromBlastingFrozenDiamond())
        server.addRecipe(diamondFromFrozenDiamondCampfireCooking())
        server.addRecipe(diamondFromSmeltingFrozenDiamond())

        server.addRecipe(emeraldFromBlastingFrozenEmerald())
        server.addRecipe(emeraldFromFrozenEmeraldCampfireCooking())
        server.addRecipe(emeraldFromSmeltingFrozenEmerald())

        server.addRecipe(lapisLazuliFromBlastingLapisLazuli())
        server.addRecipe(lapisLazuliFromFrozenLapisLazuliCampfireCooking())
        server.addRecipe(lapisLazuliFromSmeltingFrozenLapisLazuli())

        server.addRecipe(rawCopperFromBlastingFrozenRawCopper())
        server.addRecipe(rawCopperFromFrozenRawCopperCampfireCookinig())
        server.addRecipe(rawCopperFromSmeltingFrozenRawCopper())

        server.addRecipe(rawGoldFromBlastingFrozenRawGold())
        server.addRecipe(rawGoldFromFrozenRawGoldCampfireCooking())
        server.addRecipe(rawGoldFromSmeltingFrozenRawGold())

        server.addRecipe(rawIronFromBlastingFrozenRawIron())
        server.addRecipe(rawIronFromFrozenRawIronCampfireCooking())
        server.addRecipe(rawIronFromSmeltingFrozenRawIRon())

        server.addRecipe(redstoneFromBlastingFrozenRedstone())
        server.addRecipe(redstoneFromFrozenRedstoneCampfireCooking())
        server.addRecipe(redstoneFromSmeltingFrozenRedstone())
    }

    private fun coalFromBlastingFrozenCoal(): BlastingRecipe = BlastingRecipe(
        COAL_FROM_BLASTING_FROZEN_COAL,
        ItemStack.of(FrozenOre.COAL.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.COAL.item)),
        0.45f,
        50,
    )

    private fun coalFromFrozenCoalCampfireCooking(): CampfireRecipe = CampfireRecipe(
        COAL_FROM_FROZEN_COAL_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.COAL.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.COAL.item)),
        0.45f,
        200,
    )

    private fun coalFromSmeltingFrozenCoal(): FurnaceRecipe = FurnaceRecipe(
        COAL_FROM_SMELTING_FROZEN_COAL,
        ItemStack.of(FrozenOre.COAL.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.COAL.item)),
        0.45f,
        150,
    )

    private fun diamondFromBlastingFrozenDiamond(): BlastingRecipe = BlastingRecipe(
        DIAMOND_FROM_BLASTING_FROZEN_DIAMOND,
        ItemStack.of(FrozenOre.DIAMOND.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.DIAMOND.item)),
        1.15f,
        50,
    )

    private fun diamondFromFrozenDiamondCampfireCooking(): CampfireRecipe = CampfireRecipe(
        DIAMOND_FROM_FROZEN_DIAMOND_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.DIAMOND.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.DIAMOND.item)),
        1.15f,
        200,
    )

    private fun diamondFromSmeltingFrozenDiamond(): FurnaceRecipe = FurnaceRecipe(
        DIAMOND_FROM_SMELTING_FROZEN_DIAMOND,
        ItemStack.of(FrozenOre.DIAMOND.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.DIAMOND.item)),
        1.15f,
        150,
    )

    private fun emeraldFromBlastingFrozenEmerald(): BlastingRecipe = BlastingRecipe(
        EMERALD_FROM_BLASTING_FROZEN_EMERALD,
        ItemStack.of(FrozenOre.EMERALD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.EMERALD.item)),
        1.05f,
        50,
    )

    private fun emeraldFromFrozenEmeraldCampfireCooking(): CampfireRecipe = CampfireRecipe(
        EMERALD_FROM_FROZEN_EMERALD_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.EMERALD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.EMERALD.item)),
        1.05f,
        200,
    )

    private fun emeraldFromSmeltingFrozenEmerald(): FurnaceRecipe = FurnaceRecipe(
        EMERALD_FROM_SMELTING_FROZEN_EMERALD,
        ItemStack.of(FrozenOre.EMERALD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.EMERALD.item)),
        1.05f,
        150,
    )

    private fun lapisLazuliFromBlastingLapisLazuli(): BlastingRecipe = BlastingRecipe(
        LAPIS_LAZULI_FROM_BLASTING_FROZEN_LAPIS_LAZULI,
        ItemStack.of(FrozenOre.LAPIS_LAZULI.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.LAPIS_LAZULI.item)),
        0.95f,
        50,
    )

    private fun lapisLazuliFromFrozenLapisLazuliCampfireCooking(): CampfireRecipe = CampfireRecipe(
        LAPIS_LAZULI_FROM_FROZEN_LAPIS_LAZULI_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.LAPIS_LAZULI.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.LAPIS_LAZULI.item)),
        0.95f,
        200,
    )

    private fun lapisLazuliFromSmeltingFrozenLapisLazuli(): FurnaceRecipe = FurnaceRecipe(
        LAPIS_LAZULI_FROM_SMELTING_FROZEN_LAPIS_LAZULI,
        ItemStack.of(FrozenOre.LAPIS_LAZULI.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.LAPIS_LAZULI.item)),
        0.95f,
        150,
    )

    private fun rawCopperFromBlastingFrozenRawCopper(): BlastingRecipe = BlastingRecipe(
        RAW_COPPER_FROM_BLASTING_FROZEN_RAW_COPPER,
        ItemStack.of(FrozenOre.RAW_COPPER.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_COPPER.item)),
        0.55f,
        50,
    )

    private fun rawCopperFromFrozenRawCopperCampfireCookinig(): CampfireRecipe = CampfireRecipe(
        RAW_COPPER_FROM_FROZEN_RAW_COPPER_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.RAW_COPPER.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_COPPER.item)),
        0.55f,
        200,
    )

    private fun rawCopperFromSmeltingFrozenRawCopper(): FurnaceRecipe = FurnaceRecipe(
        RAW_COPPER_FROM_SMELTING_FROZEN_RAW_COPPER,
        ItemStack.of(FrozenOre.RAW_COPPER.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_COPPER.item)),
        0.55f,
        150,
    )

    private fun rawGoldFromBlastingFrozenRawGold(): BlastingRecipe = BlastingRecipe(
        RAW_GOLD_FROM_BASTING_FROZEN_RAW_GOLD,
        ItemStack.of(FrozenOre.RAW_GOLD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_GOLD.item)),
        0.75f,
        50,
    )

    private fun rawGoldFromFrozenRawGoldCampfireCooking(): CampfireRecipe = CampfireRecipe(
        RAW_GOLD_FROM_FROZEN_RAW_GOLD_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.RAW_GOLD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_GOLD.item)),
        0.75f,
        200,
    )

    private fun rawGoldFromSmeltingFrozenRawGold(): FurnaceRecipe = FurnaceRecipe(
        RAW_GOLD_FROM_SMELTING_FROZEN_RAW_GOLD,
        ItemStack.of(FrozenOre.RAW_GOLD.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_GOLD.item)),
        0.75f,
        150,
    )

    private fun rawIronFromBlastingFrozenRawIron(): BlastingRecipe = BlastingRecipe(
        RAW_IRON_FROM_BLASTING_FROZEN_RAW_IRON,
        ItemStack.of(FrozenOre.RAW_IRON.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_IRON.item)),
        0.65f,
        50,
    )

    private fun rawIronFromFrozenRawIronCampfireCooking(): CampfireRecipe = CampfireRecipe(
        RAW_IRON_FROM_FROZEN_RAW_IRON_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.RAW_IRON.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_IRON.item)),
        0.65f,
        200,
    )

    private fun rawIronFromSmeltingFrozenRawIRon(): FurnaceRecipe = FurnaceRecipe(
        RAW_IRON_FROM_SMELTING_FROZEN_RAW_IRON,
        ItemStack.of(FrozenOre.RAW_IRON.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.RAW_IRON.item)),
        0.65f,
        150,
    )

    private fun redstoneFromBlastingFrozenRedstone(): BlastingRecipe = BlastingRecipe(
        REDSTONE_FROM_BLASTING_FROZEN_REDSTONE,
        ItemStack.of(FrozenOre.REDSTONE.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.REDSTONE.item)),
        0.85f,
        50,
    )

    private fun redstoneFromFrozenRedstoneCampfireCooking(): CampfireRecipe = CampfireRecipe(
        REDSTONE_FROM_FROZEN_REDSTONE_CAMPFIRE_COOKING,
        ItemStack.of(FrozenOre.REDSTONE.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.REDSTONE.item)),
        0.85f,
        200,
    )

    private fun redstoneFromSmeltingFrozenRedstone(): FurnaceRecipe = FurnaceRecipe(
        REDSTONE_FROM_SMELTING_FROZEN_REDSTONE,
        ItemStack.of(FrozenOre.REDSTONE.base),
        RecipeChoice.ExactChoice(ItemStack.of(FrozenOre.REDSTONE.item)),
        0.85f,
        150,
    )
}
