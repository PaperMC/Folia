package net.azisaba.vanilife.packhost

import net.azisaba.packed.Packed
import net.azisaba.packed.PackedKey
import net.azisaba.packed.dsl.*
import net.azisaba.packed.lang
import net.azisaba.packed.server.configureEmbeddedServer
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.cooking.CookingItemModels
import net.azisaba.vanilife.cooking.CookingModels
import net.azisaba.vanilife.cooking.CookingTranslations
import net.azisaba.vanilife.farming.FarmingItemModels
import net.azisaba.vanilife.farming.FarmingModels
import net.azisaba.vanilife.farming.FarmingTranslations
import net.azisaba.vanilife.fishing.FishingFonts
import net.azisaba.vanilife.fishing.FishingItemModels
import net.azisaba.vanilife.fishing.FishingModels
import net.azisaba.vanilife.fishing.FishingTranslations
import net.azisaba.vanilife.forestry.ForestryTranslations
import net.azisaba.vanilife.islands.IslandsFonts
import net.azisaba.vanilife.mining.MiningItemModels
import net.azisaba.vanilife.mining.MiningModels
import net.azisaba.vanilife.mining.MiningTranslations
import net.azisaba.vanilife.npc.*
import net.azisaba.vanilife.toolswap.ToolSwapTranslations
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.text.Component
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.util.*

internal class Main : JavaPlugin() {
    override fun onEnable() {
        val packed = Packed {
            metadata {
                minFormat(75)
                maxFormat(75)
                describe(Component.text("(c) Azisaba Network"))
            }

            font {
                FishingFonts.FISH_SHADOWS to FishingFonts.fishShadows()
                IslandsFonts.WAVES to IslandsFonts.waves()
                NpcFonts.NPC_ICONS to NpcFonts.npcIcons()
                GeneralFonts.DEFAULT to GeneralFonts.default()
            }

            items {
                CookingItemModels.BAMBOO_SHOOT_ITEM to CookingItemModels.bambooShootItem()
                CookingItemModels.BAMBOO_SHOOT_RICE_ITEM to CookingItemModels.bambooShootRiceItem()
                CookingItemModels.BANANA_ITEM to CookingItemModels.bananaItem()
                CookingItemModels.BELL_PEPPER_ITEM to CookingItemModels.bellPepperItem()
                CookingItemModels.BLUEBERRY_ITEM to CookingItemModels.blueberryItem()
                CookingItemModels.BUTTER_ITEM to CookingItemModels.butterItem()
                CookingItemModels.BUCKWHEAT_ITEM to CookingItemModels.buckwheatItem()
                CookingItemModels.CHEESE_ITEM to CookingItemModels.cheeseItem()
                CookingItemModels.CHERRY_ITEM to CookingItemModels.cherryItem()
                CookingItemModels.CHILI_PEPPER_ITEM to CookingItemModels.chiliPepperItem()
                CookingItemModels.COFFEE_ITEM to CookingItemModels.coffeeItem()
                CookingItemModels.COFFEE_BEANS_ITEM to CookingItemModels.coffeeBeansItem()
                CookingItemModels.CORN_ITEM to CookingItemModels.cornItem()
                CookingItemModels.COTTON_CANDY_ITEM to CookingItemModels.cottonCandyItem()
                CookingItemModels.CUCUMBER_ITEM to CookingItemModels.cucumberItem()
                CookingItemModels.CURRY_RICE_ITEM to CookingItemModels.curryRiceItem()
                CookingItemModels.DRIED_PERSIMMON_ITEM to CookingItemModels.driedPersimmonItem()
                CookingItemModels.EEL_RICE_BOWL_ITEM to CookingItemModels.eelRiceBowlItem()
                CookingItemModels.EGGPLANT_ITEM to CookingItemModels.eggplantItem()
                CookingItemModels.FIREFLY_SQUID_ITEM to CookingItemModels.fireflySquidItem()
                CookingItemModels.FRIED_HORSE_MACKEREL_ITEM to CookingItemModels.friedHorseMackerelItem()
                CookingItemModels.GRAPE_ITEM to CookingItemModels.grapeItem()
                CookingItemModels.GREEN_ONION_ITEM to CookingItemModels.greenOnionItem()
                CookingItemModels.GRILLED_AYU_ITEM to CookingItemModels.grilledAyuItem()
                CookingItemModels.GRILLED_MACKEREL_ITEM to CookingItemModels.grilledMackerelItem()
                CookingItemModels.GRILLED_PACIFIC_SAURY_ITEM to CookingItemModels.grilledPacificSauryItem()
                CookingItemModels.GRILLED_SQUID_ITEM to CookingItemModels.grilledSquidItem()
                CookingItemModels.HAMBURG_STEAK_ITEM to CookingItemModels.hamburgSteakItem()
                CookingItemModels.JAPANESE_RADISH_ITEM to CookingItemModels.japaneseRadishItem()
                CookingItemModels.KIWI_ITEM to CookingItemModels.kiwiItem()
                CookingItemModels.LETTUCE_ITEM to CookingItemModels.lettuceItem()
                CookingItemModels.LOTUS_ROOT_ITEM to CookingItemModels.lotusRootItem()
                CookingItemModels.MARINATED_EGGPLANT_ITEM to CookingItemModels.marinatedEggplantItem()
                CookingItemModels.MELON_ITEM to CookingItemModels.melonItem()
                CookingItemModels.MISO_ITEM to CookingItemModels.misoItem()
                CookingItemModels.MISO_MACKEREL_ITEM to CookingItemModels.misoMackerelItem()
                CookingItemModels.MISO_SOUP_ITEM to CookingItemModels.misoSoupItem()
                CookingItemModels.NAPPA_CABBAGE_ITEM to CookingItemModels.nappaCabbageItem()
                CookingItemModels.NIKUJAGA_ITEM to CookingItemModels.nikujagaItem()
                CookingItemModels.ODEN_ITEM to CookingItemModels.odenItem()
                CookingItemModels.ONION_ITEM to CookingItemModels.onionItem()
                CookingItemModels.ORANGE_ITEM to CookingItemModels.orangeItem()
                CookingItemModels.PAPER_FAN_ITEM to CookingItemModels.paperFanItem()
                CookingItemModels.PARFAIT_ITEM to CookingItemModels.parfaitItem()
                CookingItemModels.PEACH_ITEM to CookingItemModels.peachItem()
                CookingItemModels.PERSIMMON_ITEM to CookingItemModels.persimmonItem()
                CookingItemModels.PIKE_CONGER_ITEM to CookingItemModels.pikeCongerItem()
                CookingItemModels.RICE_ITEM to CookingItemModels.riceItem()
                CookingItemModels.SALAD_ITEM to CookingItemModels.saladItem()
                CookingItemModels.SALMON_ROE_ITEM to CookingItemModels.salmonRoeItem()
                CookingItemModels.SALMON_ROE_SUSHI_ITEM to CookingItemModels.salmonRoeSushiItem()
                CookingItemModels.SARDINE_ITEM to CookingItemModels.sardineItem()
                CookingItemModels.SAUSAGE_ITEM to CookingItemModels.sausageItem()
                CookingItemModels.SEA_URCHIN_SUSHI_ITEM to CookingItemModels.seaUrchinSushiItem()
                CookingItemModels.SEAFOOD_RICE_FOWL_ITEM to CookingItemModels.seafoodRiceFowlItem()
                CookingItemModels.SHAVED_ICE_ITEM to CookingItemModels.shavedIceItem()
                CookingItemModels.SKIPJACK_TUNA_ITEM to CookingItemModels.skipjackTunaItem()
                CookingItemModels.SOBA_ITEM to CookingItemModels.sobaItem()
                CookingItemModels.SOFT_SERVE_ICE_CREAM_ITEM to CookingItemModels.softServeIceCreamItem()
                CookingItemModels.SOYBEANS_ITEM to CookingItemModels.soybeansItem()
                CookingItemModels.SPINACH_ITEM to CookingItemModels.spinachItem()
                CookingItemModels.SQUID_SUSHI_ITEM to CookingItemModels.squidSushiItem()
                CookingItemModels.STEAMED_RICE_ITEM to CookingItemModels.steamedRiceItem()
                CookingItemModels.STRAWBERRY_ITEM to CookingItemModels.strawberryItem()
                CookingItemModels.SWEET_POTATO_ITEM to CookingItemModels.sweetPotatoItem()
                CookingItemModels.TAKOYAKI_ITEM to CookingItemModels.takoyakiItem()
                CookingItemModels.TAMAGO_SUSHI_ITEM to CookingItemModels.tamagoSushiItem()
                CookingItemModels.TERIYAKI_YELLOWTAIL_ITEM to CookingItemModels.teriyakiYellowtailItem()
                CookingItemModels.TOMATO to CookingItemModels.tomatoItem()
                CookingItemModels.TONKATSU_ITEM to CookingItemModels.tonkatsuItem()
                CookingItemModels.TUNA_SUSHI_ITEM to CookingItemModels.tunaSushiItem()
                CookingItemModels.UDON_ITEM to CookingItemModels.udonItem()
                CookingItemModels.YAKISOBA_ITEM to CookingItemModels.yakisobaItem()

                FarmingItemModels.FERTILIZER to FarmingItemModels.wateringCan()

                FishingItemModels.CLAM to FishingItemModels.clamItem()
                FishingItemModels.CRUCIAN_CARP to FishingItemModels.crucianCarpItem()
                FishingItemModels.EEL to FishingItemModels.eelItem()
                FishingItemModels.FLATFISH to FishingItemModels.flatfishItem()
                FishingItemModels.FLOUNDER to FishingItemModels.flounderItem()
                FishingItemModels.HORSE_MACKEREL to FishingItemModels.horseMackerelItem()
                FishingItemModels.MACKEREL to FishingItemModels.mackerelItem()
                FishingItemModels.MONKFISH to FishingItemModels.monkfishItem()
                FishingItemModels.OCTOPUS to FishingItemModels.octopusItem()
                FishingItemModels.SALMON to FishingItemModels.salmonItem()
                FishingItemModels.SEA_BASS to FishingItemModels.seaBassItem()
                FishingItemModels.SEA_BREAM to FishingItemModels.seaBreamItem()
                FishingItemModels.SEA_URCHIN to FishingItemModels.seaUrchinItem()
                FishingItemModels.SPANISH_MACKEREL to FishingItemModels.spanishMackerelItem()
                FishingItemModels.SQUID to FishingItemModels.squidItem()
                FishingItemModels.SWEETFISH to FishingItemModels.sweetfishItem()
                FishingItemModels.TUNA to FishingItemModels.tunaItem()
                FishingItemModels.YELLOWTAIL to FishingItemModels.yellowtailItem()

                MiningItemModels.FROZEN_COAL to MiningItemModels.frozenCoal()
                MiningItemModels.FROZEN_DIAMOND to MiningItemModels.frozenDiamond()
                MiningItemModels.FROZEN_EMERALD to MiningItemModels.frozenEmerald()
                MiningItemModels.FROZEN_LAPIS_LAZULI to MiningItemModels.frozenLapisLazuli()
                MiningItemModels.FROZEN_RAW_COPPER to MiningItemModels.frozenRawCopper()
                MiningItemModels.FROZEN_RAW_GOLD to MiningItemModels.frozenRawGold()
                MiningItemModels.FROZEN_RAW_IRON to MiningItemModels.frozenRawIron()
                MiningItemModels.FROZEN_REDSTONE to MiningItemModels.frozenRedstone()

                NpcItemModels.EXPERIENCE to NpcItemModels.experience()
                NpcItemModels.UNREADABLE_RECIPE to NpcItemModels.unreadableRecipe()
            }

            lang {
                PackedKey.lang(
                    Vanilife.NAMESPACE,
                    Locale.US
                ) to (CookingTranslations.us() + FarmingTranslations.us() + FishingTranslations.us() + ForestryTranslations.us() + MiningTranslations.us() + NpcTranslations.us() + ToolSwapTranslations.us() + GeneralTranslations.us())
                PackedKey.lang(
                    Vanilife.NAMESPACE,
                    Locale.JAPAN
                ) to (CookingTranslations.jp() + FarmingTranslations.jp() + FishingTranslations.jp() + ForestryTranslations.jp() + MiningTranslations.jp() + NpcTranslations.jp() + ToolSwapTranslations.jp() + GeneralTranslations.jp())
            }

            models {
                CookingModels.BAMBOO_SHOOT to CookingModels.bambooShoot()
                CookingModels.BAMBOO_SHOOT_RICE to CookingModels.bambooShootRice()
                CookingModels.BANANA to CookingModels.banana()
                CookingModels.BELL_PEPPER to CookingModels.bellPepper()
                CookingModels.BLUEBERRY to CookingModels.blueberry()
                CookingModels.BUTTER to CookingModels.butter()
                CookingModels.BUCKWHEAT to CookingModels.buckwheat()
                CookingModels.CHEESE to CookingModels.cheese()
                CookingModels.CHERRY to CookingModels.cherry()
                CookingModels.CHILI_PEPPER to CookingModels.chiliPepper()
                CookingModels.COFFEE to CookingModels.coffee()
                CookingModels.COFFEE_BEANS to CookingModels.coffeeBeans()
                CookingModels.CORN to CookingModels.corn()
                CookingModels.COTTON_CANDY to CookingModels.cottonCandy()
                CookingModels.CUCUMBER to CookingModels.cucumber()
                CookingModels.CURRY_RICE to CookingModels.curryRice()
                CookingModels.DRIED_PERSIMMON to CookingModels.driedPersimmon()
                CookingModels.EEL_RICE_BOWL to CookingModels.eelRiceBowl()
                CookingModels.EGGPLANT to CookingModels.eggplant()
                CookingModels.FIREFLY_SQUID to CookingModels.fireflySquid()
                CookingModels.FRIED_HORSE_MACKEREL to CookingModels.friedHorseMackerel()
                CookingModels.GRAPE to CookingModels.grape()
                CookingModels.GREEN_ONION to CookingModels.greenOnion()
                CookingModels.GRILLED_AYU to CookingModels.grilledAyu()
                CookingModels.GRILLED_MACKEREL to CookingModels.grilledMackerel()
                CookingModels.GRILLED_PACIFIC_SAURY to CookingModels.grilledPacificSaury()
                CookingModels.GRILLED_SQUID to CookingModels.grilledSquid()
                CookingModels.HAMBURG_STEAK to CookingModels.hamburgSteak()
                CookingModels.JAPANESE_RADISH to CookingModels.japaneseRadish()
                CookingModels.KIWI to CookingModels.kiwi()
                CookingModels.LETTUCE to CookingModels.lettuce()
                CookingModels.LOTUS_ROOT to CookingModels.lotusRoot()
                CookingModels.MARINATED_EGGPLANT to CookingModels.marinatedEggplant()
                CookingModels.MELON to CookingModels.melon()
                CookingModels.MISO to CookingModels.miso()
                CookingModels.MISO_MACKEREL to CookingModels.misoMackerel()
                CookingModels.MISO_SOUP to CookingModels.misoSoup()
                CookingModels.NAPPA_CABBAGE to CookingModels.nappaCabbage()
                CookingModels.NIKUJAGA to CookingModels.nikujaga()
                CookingModels.ODEN to CookingModels.oden()
                CookingModels.ONION to CookingModels.onion()
                CookingModels.ORANGE to CookingModels.orange()
                CookingModels.PAPER_FAN to CookingModels.paperFan()
                CookingModels.PARFAIT to CookingModels.parfait()
                CookingModels.PEACH to CookingModels.peach()
                CookingModels.PERSIMMON to CookingModels.persimmon()
                CookingModels.PIKE_CONGER to CookingModels.pikeConger()
                CookingModels.RICE to CookingModels.rice()
                CookingModels.SALAD to CookingModels.salad()
                CookingModels.SALMON_ROE to CookingModels.salmonRoe()
                CookingModels.SALMON_ROE_SUSHI to CookingModels.salmonRoeSushi()
                CookingModels.SARDINE to CookingModels.sardine()
                CookingModels.SAUSAGE to CookingModels.sausage()
                CookingModels.SEA_URCHIN_SUSHI to CookingModels.seaUrchinSushi()
                CookingModels.SEAFOOD_RICE_FOWL to CookingModels.seafoodRiceFowl()
                CookingModels.SHAVED_ICE to CookingModels.shavedIce()
                CookingModels.SKIPJACK_TUNA to CookingModels.skipjackTuna()
                CookingModels.SOBA to CookingModels.soba()
                CookingModels.SOFT_SERVE_ICE_CREAM to CookingModels.softServeIceCream()
                CookingModels.SOYBEANS to CookingModels.soybeans()
                CookingModels.SPINACH to CookingModels.spinach()
                CookingModels.SQUID_SUSHI to CookingModels.squidSushi()
                CookingModels.STEAMED_RICE to CookingModels.steamedRice()
                CookingModels.STRAWBERRY to CookingModels.strawberry()
                CookingModels.SWEET_POTATO to CookingModels.sweetPotato()
                CookingModels.TAKOYAKI to CookingModels.takoyaki()
                CookingModels.TAMAGO_SUSHI to CookingModels.tamagoSushi()
                CookingModels.TERIYAKI_YELLOWTAIL to CookingModels.teriyakiYellowtail()
                CookingModels.TOMATO to CookingModels.tomato()
                CookingModels.TONKATSU to CookingModels.tonkatsu()
                CookingModels.TUNA_SUSHI to CookingModels.tunaSushi()
                CookingModels.UDON to CookingModels.udon()
                CookingModels.YAKISOBA to CookingModels.yakisoba()

                FarmingModels.FERTILIZER to FarmingModels.wateringCan()

                FishingModels.CLAM to FishingModels.clam()
                FishingModels.CRUCIAN_CARP to FishingModels.crucianCarp()
                FishingModels.EEL to FishingModels.eel()
                FishingModels.FLATFISH to FishingModels.flatfish()
                FishingModels.FLOUNDER to FishingModels.flounder()
                FishingModels.HORSE_MACKEREL to FishingModels.horseMackerel()
                FishingModels.MACKEREL to FishingModels.mackerel()
                FishingModels.MONKFISH to FishingModels.monkfish()
                FishingModels.OCTOPUS to FishingModels.octopus()
                FishingModels.SALMON to FishingModels.salmon()
                FishingModels.SEA_BASS to FishingModels.seaBass()
                FishingModels.SEA_BREAM to FishingModels.seaBream()
                FishingModels.SEA_URCHIN to FishingModels.seaUrchin()
                FishingModels.SPANISH_MACKEREL to FishingModels.spanishMackerel()
                FishingModels.SQUID to FishingModels.squid()
                FishingModels.SWEETFISH to FishingModels.sweetfish()
                FishingModels.TUNA to FishingModels.tuna()
                FishingModels.YELLOWTAIL to FishingModels.yellowtail()

                MiningModels.FROZEN_COAL to MiningModels.frozenCoal()
                MiningModels.FROZEN_DIAMOND to MiningModels.frozenDiamond()
                MiningModels.FROZEN_EMERALD to MiningModels.frozenEmerald()
                MiningModels.FROZEN_LAPIS_LAZULI to MiningModels.frozenLapisLazuli()
                MiningModels.FROZEN_RAW_COPPER to MiningModels.frozenRawCopper()
                MiningModels.FROZEN_RAW_GOLD to MiningModels.frozenRawGold()
                MiningModels.FROZEN_RAW_IRON to MiningModels.frozenRawIron()
                MiningModels.FROZEN_REDSTONE to MiningModels.frozenRedstone()

                NpcModels.EXPERIENCE to NpcModels.experience()
                NpcModels.UNREADABLE_RECIPE to NpcModels.unreadableRecipe()
            }

            sounds {
                NpcSoundEvents.NPC_READ_RECIPE to NpcSoundEvents.npcReadRecipe()
            }

            includeJavaResources(net.azisaba.vanilife.cooking.Main::class)
            includeJavaResources(net.azisaba.vanilife.farming.Main::class)
            includeJavaResources(net.azisaba.vanilife.fishing.Main::class)
            includeJavaResources(net.azisaba.vanilife.forestry.Main::class)
            includeJavaResources(net.azisaba.vanilife.islands.Main::class)
            includeJavaResources(net.azisaba.vanilife.mining.Main::class)
            includeJavaResources(net.azisaba.vanilife.npc.Main::class)
            includeJavaResources(Main::class)

            includeZip(server.pluginsFolder.toPath().resolve("BetterModel/build.zip"), "assets", "assets")
            includeZip(
                server.pluginsFolder.toPath().resolve("BetterModel/build.zip"),
                "bettermodel_legacy",
                "bettermodel_legacy"
            )
            includeZip(
                server.pluginsFolder.toPath().resolve("BetterModel/build.zip"),
                "bettermodel_modern",
                "bettermodel_modern"
            )
        }

        val configured = packed.configureEmbeddedServer(port = 8085)
        configured.embeddedServer.start(wait = false)
        val packInfo = ResourcePackInfo.resourcePackInfo(
            UUID.randomUUID(),
            URI.create("http://localhost:8085"),
            configured.computeSha1Hash()
        )

        server.pluginManager.registerEvents(PlayerListener(packInfo), this)
    }
}
