package net.azisaba.vanilife.portal.dialog

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.azisaba.vanilife.islands.getIsland
import net.azisaba.vanilife.portal.PortalTranslations
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object ReturnDialog : KoinComponent {
    private val plugin: Plugin by inject()

    fun bootstrap(builder: DialogRegistryEntry.Builder) {
        builder
            .base(
                DialogBase.builder(Component.translatable(PortalTranslations.DIALOG_VANILIFE_RETURN))
                    .body(
                        listOf(
                            DialogBody.plainMessage(
                                Component.translatable(PortalTranslations.DIALOG_VANILIFE_RETURN_CONFIRM)
                            )
                        )
                    )
                    .build()
            )
            .type(
                DialogType.confirmation(
                    ActionButton.builder(Component.translatable(PortalTranslations.DIALOG_VANILIFE_RETURN_YES))
                        .action(
                            DialogAction.customClick(
                                { _, audience ->
                                    val player = audience as? Player ?: return@customClick
                                    plugin.launch {
                                        returnToIsland(player)
                                    }
                                },
                                ClickCallback.Options
                                    .builder()
                                    .uses(ClickCallback.UNLIMITED_USES)
                                    .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                    .build()
                            )
                        )
                        .build(),
                    ActionButton.builder(Component.translatable(PortalTranslations.DIALOG_VANILIFE_RETURN_NO))
                        .build()
                )
            )
    }

    private suspend fun returnToIsland(player: Player) {
        val island = player.getIsland() ?: return
        island.addPlayer(player)
    }
}
