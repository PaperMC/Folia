package net.azisaba.vanilife.mining

import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.tags.BlockTypeTagKeys
import io.papermc.paper.registry.tag.TagKey
import io.papermc.paper.tag.PostFlattenTagRegistrar
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import org.bukkit.block.BlockType

object MiningBlockTypeTagKeys {
    val MINER_MINABLE: TagKey<BlockType> = RegistryKey.BLOCK.tagKey(Key.key(Vanilife.NAMESPACE, "miner_minable"))
    val ORES: TagKey<BlockType> = RegistryKey.BLOCK.tagKey(Key.key(Vanilife.NAMESPACE, "ores"))

    internal fun postFlatten(event: ReloadableRegistrarEvent<PostFlattenTagRegistrar<BlockType>>) {
        event.registrar().setTag(ORES, ores(event.registrar()))
        event.registrar().setTag(MINER_MINABLE, minerMinable(event.registrar()))
    }

    private fun minerMinable(tagGetter: PostFlattenTagRegistrar<BlockType>): Set<TypedKey<BlockType>> = buildSet {
        addAll(tagGetter.getTag(ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.BASE_STONE_OVERWORLD))
    }

    private fun ores(tagGetter: PostFlattenTagRegistrar<BlockType>) = buildSet {
        addAll(tagGetter.getTag(BlockTypeTagKeys.COAL_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.COPPER_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.DIAMOND_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.EMERALD_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.GOLD_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.IRON_ORES))
        addAll(tagGetter.getTag(BlockTypeTagKeys.LAPIS_ORES))
    }
}
