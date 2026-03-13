package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.cooking.CookingItems
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

object MerchantConstructors {
    val PLAINS: MerchantConstructor = MerchantConstructor {
        add(3, ItemStack.of(CookingItems.TOMATO, 2), ItemStack.of(Material.OAK_LOG, 14), 5)
        add(1, ItemStack.of(CookingItems.JAPANESE_RADISH, 1), ItemStack.of(Material.ICE, 10), 5)
        add(3, ItemStack.of(CookingItems.CUCUMBER, 2), ItemStack.of(Material.WHITE_WOOL, 6), 5)
        add(2, ItemStack.of(CookingItems.ONION, 1), ItemStack.of(Material.COBBLESTONE, 20), 5)
        add(2, ItemStack.of(CookingItems.LETTUCE, 1), ItemStack.of(Material.OAK_SAPLING, 12), 5)
        add(2, ItemStack.of(CookingItems.CORN, 2), ItemStack.of(Material.SAND, 16), 12)
        add(1, ItemStack.of(CookingItems.STRAWBERRY, 1), ItemStack.of(Material.GRAVEL, 16), 5)
        add(2, ItemStack.of(CookingItems.BELL_PEPPER, 1), ItemStack.of(Material.RED_SANDSTONE, 14), 5)
        add(2, ItemStack.of(CookingItems.EGGPLANT, 1), ItemStack.of(Material.DIORITE, 20), 5)
        add(1, ItemStack.of(CookingItems.GRAPE, 1), ItemStack.of(Material.ANDESITE, 18), 5)
        add(1, ItemStack.of(CookingItems.PEACH, 1), ItemStack.of(Material.SANDSTONE, 20), 5)
        add(1, ItemStack.of(CookingItems.BLUEBERRY, 1), ItemStack.of(Material.BIRCH_LOG, 14), 5)
    }
}
