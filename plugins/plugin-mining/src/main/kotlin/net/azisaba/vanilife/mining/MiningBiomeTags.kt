package net.azisaba.vanilife.mining

import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.BiomeKeys
import io.papermc.paper.registry.tag.TagKey
import io.papermc.paper.tag.PostFlattenTagRegistrar
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import org.bukkit.block.Biome

object MiningBiomeTags {
    val DROPS_FROZEN_ORES: TagKey<Biome> = RegistryKey.BIOME.tagKey(Key.key(Vanilife.NAMESPACE, "drops_frozen_ores"))

    internal fun postFlatten(event: ReloadableRegistrarEvent<PostFlattenTagRegistrar<Biome>>) {
        event.registrar().setTag(DROPS_FROZEN_ORES, dropsFrozenOres())
    }

    private fun dropsFrozenOres(): Set<TypedKey<Biome>> = setOf(BiomeKeys.GLACIAL_CAVE)
}
