package net.azisaba.vanilife.cooking

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.datacomponent.item.FoodProperties
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItemType
import net.azisaba.vanilife.registry.data.ServerItemTypeRegistryEntry
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object CookingItems {
    val BAMBOO_SHOOT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bamboo_shoot"))
    val BAMBOO_SHOOT_RICE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bamboo_shoot_rice"))
    val BANANA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "banana"))
    val BELL_PEPPER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bell_pepper"))
    val BLUEBERRY: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "blueberry"))
    val BUTTER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "butter"))
    val BUCKWHEAT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "buckwheat"))
    val CHEESE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cheese"))
    val CHERRY: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cherry"))
    val CHILI_PEPPER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "chili_pepper"))
    val COFFEE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "coffee"))
    val COFFEE_BEANS: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "coffee_beans"))
    val CORN: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "corn"))
    val COTTON_CANDY: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cotton_candy"))
    val CUCUMBER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cucumber"))
    val CURRY_RICE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "curry_rice"))
    val DRIED_PERSIMMON: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "dried_persimmon"))
    val EEL_RICE_BOWL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eel_rice_bowl"))
    val EGGPLANT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eggplant"))
    val FIREFLY_SQUID: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "firefly_squid"))
    val FRIED_HORSE_MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "fried_horse_mackerel"))
    val GRAPE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grape"))
    val GREEN_ONION: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "green_onion"))
    val GRILLED_AYU: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_ayu"))
    val GRILLED_MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_mackerel"))
    val GRILLED_PACIFIC_SAURY: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_pacific_saury"))
    val GRILLED_SQUID: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_squid"))
    val HAMBURG_STEAK: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "hamburg_steak"))
    val JAPANESE_RADISH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "japanese_radish"))
    val KIWI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "kiwi"))
    val LETTUCE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "lettuce"))
    val LOTUS_ROOT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "lotus_root"))
    val MARINATED_EGGPLANT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "marinated_eggplant"))
    val MELON: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "melon"))
    val MISO: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso"))
    val MISO_MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso_mackerel"))
    val MISO_SOUP: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso_soup"))
    val NAPPA_CABBAGE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "nappa_cabbage"))
    val NIKUJAGA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "nikujaga"))
    val ODEN: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "oden"))
    val ONION: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "onion"))
    val ORANGE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "orange"))
    val PAPER_FAN: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "paper_fan"))
    val PARFAIT: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "parfait"))
    val PEACH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "peach"))
    val PERSIMMON: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "persimmon"))
    val PIKE_CONGER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "pike_conger"))
    val RICE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "rice"))
    val SALAD: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salad"))
    val SALMON_ROE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon_roe"))
    val SALMON_ROE_SUSHI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon_roe_sushi"))
    val SARDINE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sardine"))
    val SAUSAGE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sausage"))
    val SEA_URCHIN_SUSHI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_urchin_sushi"))
    val SEAFOOD_RICE_FOWL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "seafood_rice_fowl"))
    val SHAVED_ICE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "shaved_ice"))
    val SKIPJACK_TUNA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "skipjack_tuna"))
    val SOBA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soba"))
    val SOFT_SERVE_ICE_CREAM: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soft_serve_ice_cream"))
    val SOYBEANS: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soybeans"))
    val SPINACH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "spinach"))
    val SQUID_SUSHI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "squid_sushi"))
    val STEAMED_RICE: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "steamed_rice"))
    val STRAWBERRY: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "strawberry"))
    val SWEET_POTATO: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sweet_potato"))
    val TAKOYAKI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "takoyaki"))
    val TAMAGO_SUSHI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tamago_sushi"))
    val TERIYAKI_YELLOWTAIL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "teriyaki_yellowtail"))
    val TOMATO: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tomato"))
    val TONKATSU: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tonkatsu"))
    val TUNA_SUSHI: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tuna_sushi"))
    val UDON: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "udon"))
    val YAKISOBA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "yakisoba"))

    fun bootstrap(event: RegistryComposeEvent<ServerItemType, ServerItemTypeRegistryEntry.Builder>) {
        event.registry().register(BAMBOO_SHOOT, ::bambooShoot)
        event.registry().register(BAMBOO_SHOOT_RICE, ::bambooShootRice)
        event.registry().register(BANANA, ::banana)
        event.registry().register(BELL_PEPPER, ::bellPepper)
        event.registry().register(BLUEBERRY, ::blueberry)
        event.registry().register(BUTTER, ::butter)
        event.registry().register(BUCKWHEAT, ::buckwheat)
        event.registry().register(CHEESE, ::cheese)
        event.registry().register(CHERRY, ::cherry)
        event.registry().register(CHILI_PEPPER, ::chiliPepper)
        event.registry().register(COFFEE, ::coffee)
        event.registry().register(COFFEE_BEANS, ::coffeeBeans)
        event.registry().register(CORN, ::corn)
        event.registry().register(COTTON_CANDY, ::cottonCandy)
        event.registry().register(CUCUMBER, ::cucumber)
        event.registry().register(CURRY_RICE, ::curryRice)
        event.registry().register(DRIED_PERSIMMON, ::driedPersimmon)
        event.registry().register(EEL_RICE_BOWL, ::eelRiceBowl)
        event.registry().register(EGGPLANT, ::eggplant)
        event.registry().register(FIREFLY_SQUID, ::fireflySquid)
        event.registry().register(FRIED_HORSE_MACKEREL, ::friedHorseMackerel)
        event.registry().register(GRAPE, ::grape)
        event.registry().register(GREEN_ONION, ::greenOnion)
        event.registry().register(GRILLED_AYU, ::grilledAyu)
        event.registry().register(GRILLED_MACKEREL, ::grilledMackerel)
        event.registry().register(GRILLED_PACIFIC_SAURY, ::grilledPacificSaury)
        event.registry().register(GRILLED_SQUID, ::grilledSquid)
        event.registry().register(HAMBURG_STEAK, ::hamburgSteak)
        event.registry().register(JAPANESE_RADISH, ::japaneseRadish)
        event.registry().register(KIWI, ::kiwi)
        event.registry().register(LETTUCE, ::lettuce)
        event.registry().register(LOTUS_ROOT, ::lotusRoot)
        event.registry().register(MARINATED_EGGPLANT, ::marinatedEggplant)
        event.registry().register(MELON, ::melon)
        event.registry().register(MISO, ::miso)
        event.registry().register(MISO_MACKEREL, ::misoMackerel)
        event.registry().register(MISO_SOUP, ::misoSoup)
        event.registry().register(NAPPA_CABBAGE, ::nappaCabbage)
        event.registry().register(NIKUJAGA, ::nikujaga)
        event.registry().register(ODEN, ::oden)
        event.registry().register(ONION, ::onion)
        event.registry().register(ORANGE, ::orange)
        event.registry().register(PAPER_FAN, ::paperFan)
        event.registry().register(PARFAIT, ::parfait)
        event.registry().register(PEACH, ::peach)
        event.registry().register(PERSIMMON, ::persimmon)
        event.registry().register(PIKE_CONGER, ::pikeConger)
        event.registry().register(RICE, ::rice)
        event.registry().register(SALAD, ::salad)
        event.registry().register(SALMON_ROE, ::salmonRoe)
        event.registry().register(SALMON_ROE_SUSHI, ::salmonRoeSushi)
        event.registry().register(SARDINE, ::sardine)
        event.registry().register(SAUSAGE, ::sausage)
        event.registry().register(SEA_URCHIN_SUSHI, ::seaUrchinSushi)
        event.registry().register(SEAFOOD_RICE_FOWL, ::seafoodRiceFowl)
        event.registry().register(SHAVED_ICE, ::shavedIce)
        event.registry().register(SKIPJACK_TUNA, ::skipjackTuna)
        event.registry().register(SOBA, ::soba)
        event.registry().register(SOFT_SERVE_ICE_CREAM, ::softServeIceCream)
        event.registry().register(SOYBEANS, ::soybeans)
        event.registry().register(SPINACH, ::spinach)
        event.registry().register(SQUID_SUSHI, ::squidSushi)
        event.registry().register(STEAMED_RICE, ::steamedRice)
        event.registry().register(STRAWBERRY, ::strawberry)
        event.registry().register(SWEET_POTATO, ::sweetPotato)
        event.registry().register(TAKOYAKI, ::takoyaki)
        event.registry().register(TAMAGO_SUSHI, ::tamagoSushi)
        event.registry().register(TERIYAKI_YELLOWTAIL, ::teriyakiYellowtail)
        event.registry().register(TOMATO, ::tomato)
        event.registry().register(TONKATSU, ::tonkatsu)
        event.registry().register(TUNA_SUSHI, ::tunaSushi)
        event.registry().register(UDON, ::udon)
        event.registry().register(YAKISOBA, ::yakisoba)
    }

    private fun bambooShoot(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BAMBOO_SHOOT))
            .itemModel(CookingItemModels.BAMBOO_SHOOT_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
            .peakSeason(*Season.SPRING.subSeasons())
    }

    private fun bambooShootRice(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BAMBOO_SHOOT_RICE))
            .itemModel(CookingItemModels.BAMBOO_SHOOT_RICE_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
            .peakSeason(*Season.SPRING.subSeasons())
    }

    private fun banana(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BANANA))
            .itemModel(CookingItemModels.BANANA_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
    }

    private fun bellPepper(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BELL_PEPPER))
            .itemModel(CookingItemModels.BELL_PEPPER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun blueberry(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BLUEBERRY))
            .itemModel(CookingItemModels.BLUEBERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun butter(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BUTTER))
            .itemModel(CookingItemModels.BUTTER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.8f).build())
    }

    private fun buckwheat(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BUCKWHEAT))
            .itemModel(CookingItemModels.BUCKWHEAT_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.2f).build())
    }

    private fun cheese(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CHEESE))
            .itemModel(CookingItemModels.CHEESE_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.8f).build())
    }

    private fun cherry(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CHERRY))
            .itemModel(CookingItemModels.CHERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*Season.SPRING.subSeasons())
    }

    private fun chiliPepper(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CHILI_PEPPER))
            .itemModel(CookingItemModels.CHILI_PEPPER_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun coffee(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_COFFEE))
            .itemModel(CookingItemModels.COFFEE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun coffeeBeans(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_COFFEE_BEANS))
            .itemModel(CookingItemModels.COFFEE_BEANS_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun corn(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CORN))
            .itemModel(CookingItemModels.CORN_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.6f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun cottonCandy(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_COTTON_CANDY))
            .itemModel(CookingItemModels.COTTON_CANDY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun cucumber(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CUCUMBER))
            .itemModel(CookingItemModels.CUCUMBER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun curryRice(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CURRY_RICE))
            .itemModel(CookingItemModels.CURRY_RICE_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun driedPersimmon(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_DRIED_PERSIMMON))
            .itemModel(CookingItemModels.DRIED_PERSIMMON_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.5f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun eelRiceBowl(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_EEL_RICE_BOWL))
            .itemModel(CookingItemModels.EEL_RICE_BOWL_ITEM)
            .food(FoodProperties.food().nutrition(10).saturation(0.8f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.MID), Season.SUMMER.withStage(Season.Stage.LATE))
    }

    private fun eggplant(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_EGGPLANT))
            .itemModel(CookingItemModels.EGGPLANT_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.3f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun fireflySquid(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_FIREFLY_SQUID))
            .itemModel(CookingItemModels.FIREFLY_SQUID_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(Season.SPRING.withStage(Season.Stage.EARLY), Season.SPRING.withStage(Season.Stage.MID))
    }

    private fun friedHorseMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_FRIED_HORSE_MACKEREL))
            .itemModel(CookingItemModels.FRIED_HORSE_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
            .peakSeason(Season.SPRING.withStage(Season.Stage.LATE), Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
    }

    private fun grape(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GRAPE))
            .itemModel(CookingItemModels.GRAPE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun greenOnion(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GREEN_ONION))
            .itemModel(CookingItemModels.GREEN_ONION_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun grilledAyu(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GRILLED_AYU))
            .itemModel(CookingItemModels.GRILLED_AYU_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID), Season.SUMMER.withStage(Season.Stage.LATE))
    }

    private fun grilledMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GRILLED_MACKEREL))
            .itemModel(CookingItemModels.GRILLED_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.8f).build())
            .peakSeason(Season.FALL.withStage(Season.Stage.EARLY), Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
    }

    private fun grilledPacificSaury(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GRILLED_PACIFIC_SAURY))
            .itemModel(CookingItemModels.GRILLED_PACIFIC_SAURY_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.8f).build())
            .peakSeason(Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
    }

    private fun grilledSquid(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_GRILLED_SQUID))
            .itemModel(CookingItemModels.GRILLED_SQUID_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
    }

    private fun hamburgSteak(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_HAMBURG_STEAK))
            .itemModel(CookingItemModels.HAMBURG_STEAK_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun japaneseRadish(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_JAPANESE_RADISH))
            .itemModel(CookingItemModels.JAPANESE_RADISH_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun kiwi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_KIWI))
            .itemModel(CookingItemModels.KIWI_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun lettuce(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_LETTUCE))
            .itemModel(CookingItemModels.LETTUCE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun lotusRoot(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_LOTUS_ROOT))
            .itemModel(CookingItemModels.LOTUS_ROOT_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun marinatedEggplant(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_MARINATED_EGGPLANT))
            .itemModel(CookingItemModels.MARINATED_EGGPLANT_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.5f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun melon(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_MELON))
            .itemModel(CookingItemModels.MELON_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun miso(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_MISO))
            .itemModel(CookingItemModels.MISO_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun misoMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_MISO_MACKEREL))
            .itemModel(CookingItemModels.MISO_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
            .peakSeason(Season.FALL.withStage(Season.Stage.EARLY), Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
    }

    private fun misoSoup(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_MISO_SOUP))
            .itemModel(CookingItemModels.MISO_SOUP_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun nappaCabbage(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_NAPPA_CABBAGE))
            .itemModel(CookingItemModels.NAPPA_CABBAGE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun nikujaga(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_NIKUJAGA))
            .itemModel(CookingItemModels.NIKUJAGA_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun oden(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_ODEN))
            .itemModel(CookingItemModels.ODEN_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun onion(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_ONION))
            .itemModel(CookingItemModels.ONION_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
            .peakSeason(*Season.SPRING.subSeasons())
    }

    private fun orange(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_ORANGE))
            .itemModel(CookingItemModels.ORANGE_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun paperFan(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_PAPER_FAN))
            .itemModel(CookingItemModels.PAPER_FAN_ITEM)
    }

    private fun parfait(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_PARFAIT))
            .itemModel(CookingItemModels.PARFAIT_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.6f).canAlwaysEat(true).build())
    }

    private fun peach(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_PEACH))
            .itemModel(CookingItemModels.PEACH_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun persimmon(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_PERSIMMON))
            .itemModel(CookingItemModels.PERSIMMON_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun pikeConger(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_PIKE_CONGER))
            .itemModel(CookingItemModels.PIKE_CONGER_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.MID), Season.SUMMER.withStage(Season.Stage.LATE))
    }

    private fun rice(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_RICE))
            .itemModel(CookingItemModels.RICE_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.2f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun salad(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SALAD))
            .itemModel(CookingItemModels.SALAD_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun salmonRoe(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SALMON_ROE))
            .itemModel(CookingItemModels.SALMON_ROE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
    }

    private fun salmonRoeSushi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SALMON_ROE_SUSHI))
            .itemModel(CookingItemModels.SALMON_ROE_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
            .peakSeason(Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
    }

    private fun sardine(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SARDINE))
            .itemModel(CookingItemModels.SARDINE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
    }

    private fun sausage(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SAUSAGE))
            .itemModel(CookingItemModels.SAUSAGE_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun seaUrchinSushi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SEA_URCHIN_SUSHI))
            .itemModel(CookingItemModels.SEA_URCHIN_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
    }

    private fun seafoodRiceFowl(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SEAFOOD_RICE_FOWL))
            .itemModel(CookingItemModels.SEAFOOD_RICE_FOWL_ITEM)
            .food(FoodProperties.food().nutrition(9).saturation(0.8f).build())
            .peakSeason(Season.WINTER.withStage(Season.Stage.EARLY), Season.WINTER.withStage(Season.Stage.MID))
    }

    private fun shavedIce(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SHAVED_ICE))
            .itemModel(CookingItemModels.SHAVED_ICE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun skipjackTuna(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SKIPJACK_TUNA))
            .itemModel(CookingItemModels.SKIPJACK_TUNA_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(Season.SPRING.withStage(Season.Stage.EARLY), Season.SPRING.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.EARLY), Season.FALL.withStage(Season.Stage.MID))
    }

    private fun soba(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SOBA))
            .itemModel(CookingItemModels.SOBA_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun softServeIceCream(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SOFT_SERVE_ICE_CREAM))
            .itemModel(CookingItemModels.SOFT_SERVE_ICE_CREAM_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.3f).canAlwaysEat(true).build())
    }

    private fun soybeans(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SOYBEANS))
            .itemModel(CookingItemModels.SOYBEANS_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun spinach(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SPINACH))
            .itemModel(CookingItemModels.SPINACH_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
            .peakSeason(*Season.WINTER.subSeasons())
    }

    private fun squidSushi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SQUID_SUSHI))
            .itemModel(CookingItemModels.SQUID_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
    }

    private fun steamedRice(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_STEAMED_RICE))
            .itemModel(CookingItemModels.STEAMED_RICE_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun strawberry(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_STRAWBERRY))
            .itemModel(CookingItemModels.STRAWBERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*Season.SPRING.subSeasons())
    }

    private fun sweetPotato(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SWEET_POTATO))
            .itemModel(CookingItemModels.SWEET_POTATO_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.6f).build())
            .peakSeason(*Season.FALL.subSeasons())
    }

    private fun takoyaki(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TAKOYAKI))
            .itemModel(CookingItemModels.TAKOYAKI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun tamagoSushi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TAMAGO_SUSHI))
            .itemModel(CookingItemModels.TAMAGO_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
    }

    private fun teriyakiYellowtail(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TERIYAKI_YELLOWTAIL))
            .itemModel(CookingItemModels.TERIYAKI_YELLOWTAIL_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
            .peakSeason(Season.WINTER.withStage(Season.Stage.EARLY), Season.WINTER.withStage(Season.Stage.MID), Season.WINTER.withStage(Season.Stage.LATE))
    }

    private fun tomato(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TOMATO))
            .itemModel(CookingItemModels.TOMATO)
            .food(FoodProperties.food().nutrition(3).saturation(0.4f).build())
            .peakSeason(*Season.SUMMER.subSeasons())
    }

    private fun tonkatsu(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TONKATSU))
            .itemModel(CookingItemModels.TONKATSU_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun tunaSushi(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TUNA_SUSHI))
            .itemModel(CookingItemModels.TUNA_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
            .peakSeason(Season.WINTER.withStage(Season.Stage.EARLY), Season.WINTER.withStage(Season.Stage.MID), Season.WINTER.withStage(Season.Stage.LATE))
    }

    private fun udon(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_UDON))
            .itemModel(CookingItemModels.UDON_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun yakisoba(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_YAKISOBA))
            .itemModel(CookingItemModels.YAKISOBA_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
    }

}
