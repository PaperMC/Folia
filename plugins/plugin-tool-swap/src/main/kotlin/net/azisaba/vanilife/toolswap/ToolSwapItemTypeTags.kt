package net.azisaba.vanilife.toolswap

import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys
import io.papermc.paper.registry.tag.TagKey
import io.papermc.paper.tag.PostFlattenTagRegistrar
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemType

object ToolSwapItemTypeTags {
    val ENCHANTABLE_TOOL_SWAP: TagKey<ItemType> = RegistryKey.ITEM.tagKey(Key.key(Vanilife.NAMESPACE, "enchantable/tool_swap"))

    internal fun postFlatten(event: ReloadableRegistrarEvent<PostFlattenTagRegistrar<ItemType>>) {
        event.registrar().setTag(ENCHANTABLE_TOOL_SWAP, enchantableToolSwap(event.registrar()))
    }

    private fun enchantableToolSwap(tagGetter: PostFlattenTagRegistrar<ItemType>): Set<TypedKey<ItemType>> = buildSet {
        addAll(tagGetter.getTag(ItemTypeTagKeys.AXES))
        addAll(tagGetter.getTag(ItemTypeTagKeys.PICKAXES))
        addAll(tagGetter.getTag(ItemTypeTagKeys.SHOVELS))
    }
}
