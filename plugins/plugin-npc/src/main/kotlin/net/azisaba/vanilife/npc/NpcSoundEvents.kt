package net.azisaba.vanilife.npc

import net.azisaba.packed.PackedKey
import net.azisaba.packed.soundEvent
import net.azisaba.packed.sounds.PackSound
import net.azisaba.packed.sounds.PackSoundEvent
import net.azisaba.packed.sounds.PackSoundType
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object NpcSoundEvents {
    val NPC_READ_RECIPE: PackedKey<PackSoundEvent> = PackedKey.soundEvent(Vanilife.NAMESPACE, "npc.read_recipe")

    fun npcReadRecipe(): PackSoundEvent = PackSoundEvent(
        sounds = listOf(
            PackSound(
                type = PackSoundType.FILE,
                name = Key.key(Vanilife.NAMESPACE, "npc/read_recipe"),
                weight = 1,
            )
        ),
    )
}
