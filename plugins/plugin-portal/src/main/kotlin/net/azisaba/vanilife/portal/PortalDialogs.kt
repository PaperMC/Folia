package net.azisaba.vanilife.portal

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.portal.dialog.ReturnDialog
import net.kyori.adventure.key.Key

object PortalDialogs {
    val RETURN: TypedKey<Dialog> = RegistryKey.DIALOG.typedKey(Key.key(Vanilife.NAMESPACE, "return"))

    internal fun bootstrap(event: RegistryComposeEvent<Dialog, DialogRegistryEntry.Builder>) {
        event.registry().register(RETURN, ReturnDialog::bootstrap)
    }
}
