package net.azisaba.vanilife.packhost

import net.azisaba.packed.Packed
import net.azisaba.packed.PackedKey
import net.azisaba.packed.dsl.font
import net.azisaba.packed.dsl.items
import net.azisaba.packed.dsl.lang
import net.azisaba.packed.dsl.models
import net.azisaba.packed.lang
import net.azisaba.packed.server.configureEmbeddedServer
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.cooking.CookingItemModels
import net.azisaba.vanilife.cooking.CookingTranslations
import net.azisaba.vanilife.islands.IslandsFonts
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.text.Component
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.util.Locale
import java.util.UUID

internal class Main : JavaPlugin() {
    override fun onEnable() {
        val packed = Packed {
            metadata {
                minFormat(75)
                maxFormat(75)
                describe(Component.text("(c) Azisaba Network"))
            }

            font {
                IslandsFonts.WAVES to IslandsFonts.waves()
            }

            items {
                CookingItemModels.BAMBOO_SHOOT_ITEM to CookingItemModels.bambooShootItem()
                CookingItemModels.CLAM_ITEM to CookingItemModels.clamItem()
                CookingItemModels.FIREFLY_SQUID_ITEM to CookingItemModels.fireflySquidItem()
                CookingItemModels.SKIPJACK_TUNA_ITEM to CookingItemModels.skipjackTunaItem()
                CookingItemModels.SPANISH_MACKEREL_ITEM to CookingItemModels.spanishMackerelItem()
                CookingItemModels.TOMATO to CookingItemModels.tomato()
            }

            lang {
                PackedKey.lang(Vanilife.NAMESPACE, Locale.US) to CookingTranslations.us()
                PackedKey.lang(Vanilife.NAMESPACE, Locale.JAPAN) to CookingTranslations.jp()
            }

            models {
                CookingItemModels.BAMBOO_SHOOT to CookingItemModels.bambooShoot()
                CookingItemModels.CLAM to CookingItemModels.clam()
                CookingItemModels.FIREFLY_SQUID to CookingItemModels.fireflySquid()
                CookingItemModels.SKIPJACK_TUNA to CookingItemModels.skipjackTuna()
                CookingItemModels.SPANISH_MACKEREL to CookingItemModels.spanishMackerel()
                CookingItemModels.TOMATO_MODEL to CookingItemModels.tomatoModel()
            }

            includeJavaResources(net.azisaba.vanilife.cooking.Main::class)
        }

        val configured = packed.configureEmbeddedServer(port = 8085)
        configured.embeddedServer.start(wait = false)
        val packInfo = ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create("http://localhost:8085"), configured.computeSha1Hash())

        server.pluginManager.registerEvents(PlayerListener(packInfo), this)
    }
}
