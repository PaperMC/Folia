package net.azisaba.vanilife.mining.combo

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys
import kr.toxicity.hud.api.BetterHud
import kr.toxicity.hud.api.placeholder.HudPlaceholder
import net.azisaba.vanilife.mining.Main
import org.bukkit.entity.Player

internal fun Main.setupMiningComboBetterHudPlaceholders() {
    val betterHud = BetterHud.getInstance()

    betterHud.placeholderManager.stringContainer.addPlaceholder(
        "item_in_main_hand",
        HudPlaceholder.of(
            HudPlaceholder.PlaceholderFunction.of { hudPlayer ->
                val player = hudPlayer.handle() as? Player ?: return@of null
                val itemStack = player.equipment.itemInMainHand
                return@of itemStack.type.key().value()
            }
        )
    )

    betterHud.placeholderManager.numberContainer.addPlaceholder(
        "mining_combo",
        HudPlaceholder.of(
            HudPlaceholder.PlaceholderFunction.of { hudPlayer ->
                val player = hudPlayer.handle() as? Player ?: return@of 0
                val counter = ComboCounterManager.getOrCreate(player)
                return@of counter.read()
            }
        )
    )

    betterHud.placeholderManager.booleanContainer.addPlaceholder(
        "mining_combo_visible",
        HudPlaceholder.of(
            HudPlaceholder.PlaceholderFunction.of { hudPlayer ->
                val player = hudPlayer.handle() as? Player ?: return@of false
                val itemStack = player.inventory.itemInMainHand
                return@of RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ITEM)
                    .getTag(ItemTypeTagKeys.PICKAXES)
                    .contains(itemStack.type.asItemType()!!.key())
            }
        )
    )
}
