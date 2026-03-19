package net.azisaba.vanilife.islands.portal

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.registry.keys.DialogKeys
import io.papermc.paper.registry.keys.tags.DialogTagKeys
import io.papermc.paper.tag.PostFlattenTagRegistrar
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.koin.java.KoinJavaComponent.inject

object PortalDialog {
    private val plugin: Plugin by inject(Plugin::class.java)
    private val resourceTeleporter: ResourceTeleporter by inject(ResourceTeleporter::class.java)
    val ISLAND_PORTAL = DialogKeys.create(Key.key("vanilife:island_portal_dialog"))

    fun register(event: RegistryComposeEvent<Dialog, DialogRegistryEntry.Builder>) {
        event.registry().register(
            ISLAND_PORTAL,
        ) { builder ->
            builder
                .base(DialogBase.builder(Component.text("Back to island")).build())
                .type(
                    DialogType.confirmation(
                        ActionButton
                            .builder(Component.text("戻る"))
                            .action(
                                DialogAction.customClick(
                                    { _, audience ->
                                        if (audience !is Player) return@customClick
                                        audience.closeDialog()
                                        plugin.launch {
                                            resourceTeleporter.teleportResourceToIsland(audience)
                                        }
                                    },
                                    ClickCallback.Options
                                        .builder()
                                        .uses(1)
                                        .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                        .build(),
                                ),
                            ).build(),
                        ActionButton
                            .builder(Component.text("戻らない"))
                            .action(
                                DialogAction.customClick(
                                    { view, audience ->
                                        if (audience !is Player) return@customClick
                                        audience.closeDialog()
                                    },
                                    ClickCallback.Options
                                        .builder()
                                        .uses(1)
                                        .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                        .build(),
                                ),
                            ).build(),
                    ),
                )
        }
    }

    fun registerTag(event: ReloadableRegistrarEvent<PostFlattenTagRegistrar<Dialog>>) {
        event.registrar().addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, listOf(PortalDialog.ISLAND_PORTAL))
    }
}
