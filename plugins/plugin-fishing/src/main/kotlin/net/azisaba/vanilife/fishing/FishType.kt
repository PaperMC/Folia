package net.azisaba.vanilife.fishing

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.fishing.ai.DirectEscapeFightingBehavior
import net.azisaba.vanilife.fishing.ai.FishBehavior
import net.azisaba.vanilife.fishing.ai.StraightApproachBehavior
import net.azisaba.vanilife.fishing.ai.WavyApproachBehavior
import net.azisaba.vanilife.item.ServerItemType
import net.kyori.adventure.translation.Translatable

data class FishType(
    val translationKey: String,
    val item: TypedKey<ServerItemType>,
    val rarity: Float,
    val peakSeason: Set<Season.Sub>,
    val behavior: FishBehavior,
) : Translatable {
    override fun translationKey(): String = translationKey

    fun unwrapItem(): ServerItemType {
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SERVER_ITEM)
        return registry.getOrThrow(item)
    }

    companion object {
        private val SET: MutableSet<FishType> = mutableSetOf()

        val CLAM: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_CLAM,
                FishItems.CLAM,
                0.18f,
                setOf(
                    Season.WINTER.withStage(Season.Stage.LATE),
                    Season.SPRING.withStage(Season.Stage.EARLY),
                    Season.SPRING.withStage(Season.Stage.MID),
                    Season.FALL.withStage(Season.Stage.EARLY),
                    Season.FALL.withStage(Season.Stage.MID),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.05, finishDistance = 0.04),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.024,
                            minSpeed = 0.01,
                            minDistance = 0.02,
                            baseLeadDistance = 0.04,
                            leadDistanceFactor = 0.66,
                            fallbackPhaseStep = 0.18,
                        ),
                    ),
                ),
            )
        )

        val CRUCIAN_CARP: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_CRUCIAN_CARP,
                FishItems.CRUCIAN_CARP,
                0.22f,
                setOf(
                    *Season.WINTER.subSeasons(),
                    *Season.SPRING.subSeasons(),
                    Season.SUMMER.withStage(Season.Stage.EARLY),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.08,
                            finishDistance = 0.06,
                            maxSway = 0.04,
                            verticalWave = 0.003,
                            swayDistanceFactor = 0.16,
                            phaseStep = 0.42,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.058,
                            minSpeed = 0.022,
                            minDistance = 0.02,
                            baseLeadDistance = 0.09,
                            leadDistanceFactor = 0.86,
                            fallbackPhaseStep = 0.26,
                        ),
                    ),
                ),
            )
        )

        val EEL: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_EEL,
                FishItems.EEL,
                0.72f,
                setOf(
                    Season.FALL.withStage(Season.Stage.LATE),
                    Season.WINTER.withStage(Season.Stage.EARLY),
                    Season.WINTER.withStage(Season.Stage.MID),
                    *Season.SUMMER.subSeasons(),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.11,
                            finishDistance = 0.06,
                            maxSway = 0.08,
                            verticalWave = 0.005,
                            swayDistanceFactor = 0.26,
                            phaseStep = 0.72,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.105,
                            minSpeed = 0.04,
                            minDistance = 0.02,
                            baseLeadDistance = 0.125,
                            leadDistanceFactor = 1.02,
                            fallbackPhaseStep = 0.48,
                        ),
                    ),
                ),
            )
        )

        val FLATFISH: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_FLATFISH,
                FishItems.FLATFISH,
                0.44f,
                setOf(
                    Season.FALL.withStage(Season.Stage.LATE),
                    *Season.WINTER.subSeasons(),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.06, finishDistance = 0.05),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.046,
                            minSpeed = 0.018,
                            minDistance = 0.02,
                            baseLeadDistance = 0.068,
                            leadDistanceFactor = 0.8,
                            fallbackPhaseStep = 0.22,
                        ),
                    ),
                ),
            )
        )

        val FLOUNDER: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_FLOUNDER,
                FishItems.FLOUNDER,
                0.58f,
                setOf(
                    *Season.WINTER.subSeasons(),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.07, finishDistance = 0.05),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.052,
                            minSpeed = 0.021,
                            minDistance = 0.02,
                            baseLeadDistance = 0.074,
                            leadDistanceFactor = 0.84,
                            fallbackPhaseStep = 0.24,
                        ),
                    ),
                ),
            )
        )

        val HORSE_MACKEREL: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_HORSE_MACKEREL,
                FishItems.HORSE_MACKEREL,
                0.28f,
                setOf(
                    Season.SPRING.withStage(Season.Stage.LATE),
                    *Season.SUMMER.subSeasons(),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.1,
                            finishDistance = 0.06,
                            maxSway = 0.06,
                            verticalWave = 0.004,
                            swayDistanceFactor = 0.22,
                            phaseStep = 0.6,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.098,
                            minSpeed = 0.035,
                            minDistance = 0.02,
                            baseLeadDistance = 0.124,
                            leadDistanceFactor = 0.98,
                            fallbackPhaseStep = 0.38,
                        ),
                    ),
                ),
            )
        )

        val MACKEREL: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_MACKEREL,
                FishItems.MACKEREL,
                0.34f,
                setOf(
                    Season.FALL.withStage(Season.Stage.MID),
                    Season.FALL.withStage(Season.Stage.LATE),
                    *Season.WINTER.subSeasons(),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.11,
                            finishDistance = 0.06,
                            maxSway = 0.06,
                            verticalWave = 0.004,
                            swayDistanceFactor = 0.22,
                            phaseStep = 0.58,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.104,
                            minSpeed = 0.04,
                            minDistance = 0.02,
                            baseLeadDistance = 0.136,
                            leadDistanceFactor = 1.0,
                            fallbackPhaseStep = 0.36,
                        ),
                    ),
                ),
            )
        )

        val MONKFISH: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_MONKFISH,
                FishItems.MONKFISH,
                0.7f,
                setOf(
                    *Season.WINTER.subSeasons(),
                    Season.SPRING.withStage(Season.Stage.EARLY),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.055, finishDistance = 0.05),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.04,
                            minSpeed = 0.017,
                            minDistance = 0.02,
                            baseLeadDistance = 0.062,
                            leadDistanceFactor = 0.76,
                            fallbackPhaseStep = 0.18,
                        ),
                    ),
                ),
            )
        )

        val OCTOPUS: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_OCTOPUS,
                FishItems.OCTOPUS,
                0.62f,
                setOf(
                    Season.SUMMER.withStage(Season.Stage.MID),
                    Season.SUMMER.withStage(Season.Stage.LATE),
                    *Season.WINTER.subSeasons(),
                    Season.SPRING.withStage(Season.Stage.EARLY),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.075,
                            finishDistance = 0.055,
                            maxSway = 0.075,
                            verticalWave = 0.004,
                            swayDistanceFactor = 0.28,
                            phaseStep = 0.5,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.075,
                            minSpeed = 0.03,
                            minDistance = 0.02,
                            baseLeadDistance = 0.102,
                            leadDistanceFactor = 0.88,
                            fallbackPhaseStep = 0.44,
                        ),
                    ),
                ),
            )
        )

        val SALMON: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SALMON,
                FishItems.SALMON,
                0.48f,
                setOf(
                    Season.SPRING.withStage(Season.Stage.MID),
                    Season.SPRING.withStage(Season.Stage.LATE),
                    Season.SUMMER.withStage(Season.Stage.EARLY),
                    Season.SUMMER.withStage(Season.Stage.MID),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.11, finishDistance = 0.06),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.098,
                            minSpeed = 0.04,
                            minDistance = 0.02,
                            baseLeadDistance = 0.136,
                            leadDistanceFactor = 0.98,
                            fallbackPhaseStep = 0.32,
                        ),
                    ),
                ),
            )
        )

        val SEA_BASS: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SEA_BASS,
                FishItems.SEA_BASS,
                0.52f,
                setOf(
                    *Season.SUMMER.subSeasons(),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.105,
                            finishDistance = 0.06,
                            maxSway = 0.055,
                            verticalWave = 0.004,
                            swayDistanceFactor = 0.2,
                            phaseStep = 0.56,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.092,
                            minSpeed = 0.035,
                            minDistance = 0.02,
                            baseLeadDistance = 0.124,
                            leadDistanceFactor = 0.95,
                            fallbackPhaseStep = 0.34,
                        ),
                    ),
                ),
            )
        )

        val SEA_BREAM: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SEA_BREAM,
                FishItems.SEA_BREAM,
                0.66f,
                setOf(
                    *Season.SPRING.subSeasons(),
                    Season.SUMMER.withStage(Season.Stage.EARLY),
                    *Season.FALL.subSeasons(),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.095,
                            finishDistance = 0.06,
                            maxSway = 0.05,
                            verticalWave = 0.0035,
                            swayDistanceFactor = 0.18,
                            phaseStep = 0.5,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.08,
                            minSpeed = 0.032,
                            minDistance = 0.02,
                            baseLeadDistance = 0.108,
                            leadDistanceFactor = 0.88,
                            fallbackPhaseStep = 0.3,
                        ),
                    ),
                ),
            )
        )

        val SEA_URCHIN: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SEA_URCHIN,
                FishItems.SEA_URCHIN,
                0.74f,
                setOf(
                    *Season.SUMMER.subSeasons(),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.04, finishDistance = 0.04),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.018,
                            minSpeed = 0.007,
                            minDistance = 0.02,
                            baseLeadDistance = 0.034,
                            leadDistanceFactor = 0.56,
                            fallbackPhaseStep = 0.16,
                        ),
                    ),
                ),
            )
        )

        val SPANISH_MACKEREL: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SPANISH_MACKEREL,
                FishItems.SPANISH_MACKEREL,
                0.54f,
                setOf(
                    *Season.WINTER.subSeasons(),
                    *Season.SPRING.subSeasons(),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.12, finishDistance = 0.06),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.115,
                            minSpeed = 0.045,
                            minDistance = 0.02,
                            baseLeadDistance = 0.148,
                            leadDistanceFactor = 1.06,
                            fallbackPhaseStep = 0.34,
                        ),
                    ),
                ),
            )
        )

        val SQUID: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SQUID,
                FishItems.SQUID,
                0.36f,
                setOf(
                    *Season.SUMMER.subSeasons(),
                    Season.WINTER.withStage(Season.Stage.EARLY),
                    Season.WINTER.withStage(Season.Stage.MID),
                ),
                FishBehavior(
                    approach = WavyApproachBehavior(
                        WavyApproachBehavior.Configuration(
                            speed = 0.085,
                            finishDistance = 0.055,
                            maxSway = 0.085,
                            verticalWave = 0.005,
                            swayDistanceFactor = 0.3,
                            phaseStep = 0.46,
                        ),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.08,
                            minSpeed = 0.03,
                            minDistance = 0.02,
                            baseLeadDistance = 0.102,
                            leadDistanceFactor = 0.88,
                            fallbackPhaseStep = 0.4,
                        ),
                    ),
                ),
            )
        )

        val SWEETFISH: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_SWEETFISH,
                FishItems.SWEETFISH,
                0.4f,
                setOf(
                    *Season.SUMMER.subSeasons(),
                    Season.FALL.withStage(Season.Stage.EARLY),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.1, finishDistance = 0.06),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.092,
                            minSpeed = 0.035,
                            minDistance = 0.02,
                            baseLeadDistance = 0.114,
                            leadDistanceFactor = 0.92,
                            fallbackPhaseStep = 0.3,
                        ),
                    ),
                ),
            )
        )

        val TUNA: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_TUNA,
                FishItems.TUNA,
                0.9f,
                setOf(
                    Season.WINTER.withStage(Season.Stage.EARLY),
                    Season.WINTER.withStage(Season.Stage.MID),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.13, finishDistance = 0.06),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.126,
                            minSpeed = 0.05,
                            minDistance = 0.02,
                            baseLeadDistance = 0.17,
                            leadDistanceFactor = 1.08,
                            fallbackPhaseStep = 0.32,
                        ),
                    ),
                ),
            )
        )

        val YELLOWTAIL: FishType = register(
            FishType(
                FishTranslations.FISH_VANILIFE_YELLOWTAIL,
                FishItems.YELLOWTAIL,
                0.78f,
                setOf(
                    Season.FALL.withStage(Season.Stage.LATE),
                    *Season.WINTER.subSeasons(),
                ),
                FishBehavior(
                    approach = StraightApproachBehavior(
                        StraightApproachBehavior.Configuration(speed = 0.12, finishDistance = 0.06),
                    ),
                    fighting = DirectEscapeFightingBehavior(
                        DirectEscapeFightingBehavior.Configuration(
                            speed = 0.115,
                            minSpeed = 0.045,
                            minDistance = 0.02,
                            baseLeadDistance = 0.148,
                            leadDistanceFactor = 1.03,
                            fallbackPhaseStep = 0.32,
                        ),
                    ),
                ),
            )
        )

        fun allTypes(): Set<FishType> = SET.toSet()

        private fun register(fishType: FishType): FishType {
            SET.add(fishType)
            return fishType
        }
    }
}
