package net.azisaba.vanilife.fishing.game

import com.github.shynixn.mccoroutine.folia.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import net.azisaba.vanilife.fishing.FishingContext
import org.bukkit.entity.FishHook
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class FishingGameManager(private val plugin: Plugin) {
    private val waitingJobsByFishHook: MutableMap<UUID, Job> = ConcurrentHashMap()

    private val gameInstancesByFishHook: MutableMap<UUID, GameInstance> = ConcurrentHashMap<UUID, GameInstance>()

    fun startGame(context: FishingContext) {
        cancelWaiting(context.fishHook)
        val fishingGame = FishingGame(plugin, context)
        val job = plugin.launch {
            while (isActive) {
                if (!fishingGame.tick()) {
                    break
                }
                delay(50L)
            }
            fishingGame.cleanup()
            gameInstancesByFishHook.remove(context.fishHook.uniqueId)
        }
        gameInstancesByFishHook[context.fishHook.uniqueId] = GameInstance(job, fishingGame,)
    }

    fun startWaiting(context: FishingContext) {
        cancelWaiting(context.fishHook)

        BetterModel.model("fishing_bobber")
            .orElseThrow()
            .create(BukkitEntity(context.fishHook))

        waitingJobsByFishHook[context.fishHook.uniqueId] = plugin.launch {
            while (isActive && context.fishHook.isValid) {
                if (!currentCoroutineContext().isActive || !context.fishHook.isValid) {
                    break
                }

                if (Random.nextFloat() < 0.35f) {
                    startGame(context)
                    break
                }

                delay(50L * 20)
            }
            waitingJobsByFishHook.remove(context.fishHook.uniqueId)
        }
    }

    fun cancelWaiting(fishHook: FishHook) {
        waitingJobsByFishHook.remove(fishHook.uniqueId)?.let { job ->
            if (job.isActive) {
                job.cancel()
            }
        }
    }

    fun pullFishHook(player: Player): Boolean {
        val fishHook = player.fishHook ?: return false
        val gameInstance = gameInstancesByFishHook[fishHook.uniqueId] ?: return false
        gameInstance.game.pullFishHook()
        return true
    }

    private data class GameInstance(val job: Job, val game: FishingGame)
}
