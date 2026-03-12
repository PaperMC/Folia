package net.azisaba.vanilife.fishing.ai

import io.papermc.paper.math.FinePosition

data class FishBehavior(val approach: ApproachBehavior, val fighting: FightingBehavior) {
    fun interface ApproachBehavior {
        fun tick(
            time: Long,
            position: FinePosition,
            fishHookPosition: FinePosition,
        ): FinePosition?
    }

    fun interface FightingBehavior {
        fun tick(
            time: Long,
            position: FinePosition,
            fishHookPosition: FinePosition,
        ): FinePosition
    }
}
