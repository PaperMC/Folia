package net.azisaba.vanilife.farming.block

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.azisaba.vanilife.farming.FarmingTranslations
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

internal class WrapperCropDropBonus : WrapperEntity(EntityTypes.TEXT_DISPLAY) {
    override fun spawn(location: Location, parent: EntityContainer): Boolean {
        if (!super.spawn(location, parent)) return false

        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.text = Component.translatable(
                FarmingTranslations.CROP_DROP_BONUS,
                NamedTextColor.GREEN,
            )
            meta.billboardConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
        }
        refresh()

        return true
    }
}
