package net.azisaba.vanilife.portal

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.registry.keys.tags.DialogTagKeys
import io.papermc.paper.tag.PostFlattenTagRegistrar

object PortalDialogTags {
    internal fun bootstrap(event: ReloadableRegistrarEvent<PostFlattenTagRegistrar<Dialog>>) {
        event.registrar().addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, listOf(PortalDialogs.RETURN))
    }
}
