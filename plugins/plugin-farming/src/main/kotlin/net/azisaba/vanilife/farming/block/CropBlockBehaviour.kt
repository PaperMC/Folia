package net.azisaba.vanilife.farming.block

import com.github.shynixn.mccoroutine.folia.launch
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.delay
import me.tofaa.entitylib.container.EntityContainer
import net.azisaba.vanilife.farming.Crop
import net.kyori.adventure.sound.Sound
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Farmland
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.math.floor
import kotlin.random.Random

internal open class CropBlockBehaviour {
    private val wrapperEntityContainer: EntityContainer = EntityContainer.basic()

    fun blockPlace(block: Block, crop: Crop) {
        if (crop.isFullyGrownBlock(block.blockData)) return
        if (!isAtMaxMoisture(block)) return

        block.world.spawnParticle(
            Particle.HEART,
            block.location.add(0.5, 0.75, 0.5),
            6,
            0.18,
            0.28,
            0.18,
            0.01,
        )
    }

    fun randomTick(block: Block, random: Random) {
        if (block.lightLevel < 9) return

        val ageable = block.blockData as? Ageable ?: return
        if (ageable.age >= ageable.maximumAge) return

        if (!shouldGrow(block, random)) return

        ageable.age += 1
        block.blockData = ageable

        if (isAtMaxMoisture(block)) {
            block.world.spawnParticle(
                Particle.HEART,
                block.location.add(0.5, 0.75, 0.5),
                6,
                0.18,
                0.28,
                0.18,
                0.01,
            )
        }
    }

    fun applyDropBonus(
        block: Block,
        blockState: BlockState,
        player: Player?,
        vanillaDrops: List<ItemStack>,
        plugin: Plugin,
    ): List<ItemStack> {
        val crop = Crop.byBlock(blockState.type) ?: return vanillaDrops
        if (!crop.isFullyGrownBlock(blockState.blockData)) return vanillaDrops

        if (!isAtMaxMoisture(block)) return vanillaDrops

        if (Random.nextDouble() > 0.42) return vanillaDrops

        player?.playSound(Sound.sound(SoundEventKeys.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.BLOCK, 0.7f, 1.25f))

        val textDisplay = WrapperCropDropBonus()
        textDisplay.spawn(
            SpigotConversionUtil.fromBukkitLocation(block.location.add(0.5, 0.5, 0.5)),
            wrapperEntityContainer,
        )
        player?.let { textDisplay.addViewer(it.uniqueId) }
        plugin.launch {
            delay(50L * 15)
            textDisplay.remove()
        }

        return vanillaDrops.map { drop ->
            if (crop.unwrapItem() == drop.type.asItemType()) {
                drop.clone().apply { add(1) }
            } else drop
        }
    }

    private fun shouldGrow(block: Block, random: Random): Boolean {
        if (isAtMaxMoisture(block)) {
            return random.nextFloat() < 0.75f
        }

        val growthSpeed = computeGrowthSpeed(block)
        val bound = floor((25.0f / growthSpeed) + 1.0f).toInt()
        return random.nextInt(bound) == 0
    }

    private fun computeGrowthSpeed(block: Block): Float {
        var speed = 1.0f
        val blockBelow = block.getRelative(BlockFace.DOWN)

        for (dz in -1..1) {
            for (dx in -1..1) {
                var contribution = 0.0f

                val farmland = blockBelow.getRelative(dx, 0, dz).blockData as? Farmland
                if (farmland != null) {
                    contribution = growthContributionOf(farmland)
                }

                if (dx != 0 || dz != 0) {
                    contribution /= 4.0f
                }

                speed += contribution
            }
        }

        val north = block.getRelative(BlockFace.NORTH)
        val south = block.getRelative(BlockFace.SOUTH)
        val west = block.getRelative(BlockFace.WEST)
        val east = block.getRelative(BlockFace.EAST)

        val sameHorizontal = west.type == block.type || east.type == block.type
        val sameVertical = north.type == block.type || south.type == block.type
        if (sameHorizontal && sameVertical) {
            speed /= 2.0f
        } else {
            val sameDiagonal = west.getRelative(BlockFace.NORTH).type == block.type
                    || east.getRelative(BlockFace.NORTH).type == block.type
                    || east.getRelative(BlockFace.SOUTH).type == block.type
                    || west.getRelative(BlockFace.SOUTH).type == block.type
            if (sameDiagonal) {
                speed /= 2.0f
            }
        }

        return speed
    }

    private fun growthContributionOf(farmland: Farmland): Float = when {
        farmland.moisture <= 0 -> 1.0f
        else -> 3.0f
    }

    private fun isAtMaxMoisture(block: Block): Boolean {
        val farmland = block.getRelative(BlockFace.DOWN).blockData as? Farmland ?: return false
        return farmland.moisture >= farmland.maximumMoisture
    }

    companion object Default : CropBlockBehaviour()
}
