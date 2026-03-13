package net.azisaba.vanilife.cooking

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.datacomponent.item.FoodProperties
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object CookingItems {
    val BAMBOO_SHOOT: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bamboo_shoot"))
    val BAMBOO_SHOOT_RICE: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bamboo_shoot_rice"))
    val BANANA: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "banana"))
    val BELL_PEPPER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "bell_pepper"))
    val BLUEBERRY: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "blueberry"))
    val BUTTER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "butter"))
    val BUCKWHEAT: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "buckwheat"))
    val CHEESE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cheese"))
    val CHERRY: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cherry"))
    val CHILI_PEPPER: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "chili_pepper"))
    val COFFEE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "coffee"))
    val COFFEE_BEANS: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "coffee_beans"))
    val CORN: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "corn"))
    val COTTON_CANDY: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cotton_candy"))
    val CUCUMBER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cucumber"))
    val CURRY_RICE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "curry_rice"))
    val DRIED_PERSIMMON: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "dried_persimmon"))
    val EEL_RICE_BOWL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eel_rice_bowl"))
    val EGGPLANT: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eggplant"))
    val FIREFLY_SQUID: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "firefly_squid"))
    val FRIED_HORSE_MACKEREL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "fried_horse_mackerel"))
    val GRAPE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grape"))
    val GREEN_ONION: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "green_onion"))
    val GRILLED_AYU: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_ayu"))
    val GRILLED_MACKEREL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_mackerel"))
    val GRILLED_PACIFIC_SAURY: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_pacific_saury"))
    val GRILLED_SQUID: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "grilled_squid"))
    val HAMBURG_STEAK: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "hamburg_steak"))
    val JAPANESE_RADISH: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "japanese_radish"))
    val KIWI: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "kiwi"))
    val LETTUCE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "lettuce"))
    val LOTUS_ROOT: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "lotus_root"))
    val MARINATED_EGGPLANT: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "marinated_eggplant"))
    val MELON: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "melon"))
    val MISO: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso"))
    val MISO_MACKEREL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso_mackerel"))
    val MISO_SOUP: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "miso_soup"))
    val NAPPA_CABBAGE: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "nappa_cabbage"))
    val NIKUJAGA: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "nikujaga"))
    val ODEN: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "oden"))
    val ONION: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "onion"))
    val ORANGE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "orange"))
    val PAPER_FAN: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "paper_fan"))
    val PARFAIT: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "parfait"))
    val PEACH: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "peach"))
    val PERSIMMON: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "persimmon"))
    val PIKE_CONGER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "pike_conger"))
    val RICE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "rice"))
    val SALAD: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salad"))
    val SALMON_ROE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon_roe"))
    val SALMON_ROE_SUSHI: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon_roe_sushi"))
    val SARDINE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sardine"))
    val SAUSAGE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sausage"))
    val SEA_URCHIN_SUSHI: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_urchin_sushi"))
    val SEAFOOD_RICE_FOWL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "seafood_rice_fowl"))
    val SHAVED_ICE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "shaved_ice"))
    val SKIPJACK_TUNA: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "skipjack_tuna"))
    val SOBA: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soba"))
    val SOFT_SERVE_ICE_CREAM: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soft_serve_ice_cream"))
    val SOYBEANS: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "soybeans"))
    val SPINACH: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "spinach"))
    val SQUID_SUSHI: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "squid_sushi"))
    val STEAMED_RICE: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "steamed_rice"))
    val STRAWBERRY: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "strawberry"))
    val SWEET_POTATO: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sweet_potato"))
    val TAKOYAKI: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "takoyaki"))
    val TAMAGO_SUSHI: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tamago_sushi"))
    val TERIYAKI_YELLOWTAIL: TypedKey<ServerItem> =
        RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "teriyaki_yellowtail"))
    val TOMATO: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tomato"))
    val TONKATSU: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tonkatsu"))
    val TUNA_SUSHI: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tuna_sushi"))
    val UDON: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "udon"))
    val YAKISOBA: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "yakisoba"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
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

    private fun bambooShoot(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BAMBOO_SHOOT)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SPRING.subSeasons())
            .itemModel(CookingItemModels.BAMBOO_SHOOT_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun bambooShootRice(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BAMBOO_SHOOT_RICE)
            .category(ServerItemCategory.FOOD)
            .peakSeason(*Season.SPRING.subSeasons())
            .itemModel(CookingItemModels.BAMBOO_SHOOT_RICE_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
    }

    private fun banana(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BANANA)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(
                Season.SUMMER.withStage(Season.Stage.MID),
                Season.SUMMER.withStage(Season.Stage.LATE),
                Season.FALL.withStage(Season.Stage.EARLY)
            )
            .itemModel(CookingItemModels.BANANA_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
    }

    private fun bellPepper(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BELL_PEPPER)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.BELL_PEPPER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun blueberry(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BLUEBERRY)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.BLUEBERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun butter(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BUTTER)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(CookingItemModels.BUTTER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.8f).build())
    }

    private fun buckwheat(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_BUCKWHEAT)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(CookingItemModels.BUCKWHEAT_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.2f).build())
    }

    private fun cheese(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CHEESE)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(CookingItemModels.CHEESE_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.8f).build())
    }

    private fun cherry(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CHERRY)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.SPRING.subSeasons())
            .itemModel(CookingItemModels.CHERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun chiliPepper(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CHILI_PEPPER)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(
                Season.SUMMER.withStage(Season.Stage.MID),
                Season.SUMMER.withStage(Season.Stage.LATE),
                Season.FALL.withStage(Season.Stage.EARLY),
                Season.FALL.withStage(Season.Stage.MID)
            )
            .itemModel(CookingItemModels.CHILI_PEPPER_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun coffee(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_COFFEE)
            .category(ServerItemCategory.DRINK)
            .itemModel(CookingItemModels.COFFEE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun coffeeBeans(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_COFFEE_BEANS)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(CookingItemModels.COFFEE_BEANS_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun corn(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CORN)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.CORN_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.6f).build())
    }

    private fun cottonCandy(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_COTTON_CANDY)
            .category(ServerItemCategory.DESSERT)
            .itemModel(CookingItemModels.COTTON_CANDY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun cucumber(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CUCUMBER)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.CUCUMBER_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun curryRice(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_CURRY_RICE)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.CURRY_RICE_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun driedPersimmon(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_DRIED_PERSIMMON)
            .category(ServerItemCategory.FRUIT)
            .itemModel(CookingItemModels.DRIED_PERSIMMON_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.5f).build())
    }

    private fun eelRiceBowl(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_EEL_RICE_BOWL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.EEL_RICE_BOWL_ITEM)
            .food(FoodProperties.food().nutrition(10).saturation(0.8f).build())
    }

    private fun eggplant(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_EGGPLANT)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.EGGPLANT_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.3f).build())
    }

    private fun fireflySquid(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_FIREFLY_SQUID)
            .category(ServerItemCategory.FISH)
            .itemModel(CookingItemModels.FIREFLY_SQUID_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun friedHorseMackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_FRIED_HORSE_MACKEREL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.FRIED_HORSE_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
    }

    private fun grape(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GRAPE)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.FALL.subSeasons())
            .itemModel(CookingItemModels.GRAPE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun greenOnion(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GREEN_ONION)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(
                Season.FALL.withStage(Season.Stage.LATE),
                *Season.WINTER.subSeasons(),
                Season.SPRING.withStage(Season.Stage.EARLY)
            )
            .itemModel(CookingItemModels.GREEN_ONION_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.1f).build())
    }

    private fun grilledAyu(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GRILLED_AYU)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.GRILLED_AYU_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun grilledMackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GRILLED_MACKEREL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.GRILLED_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.8f).build())
    }

    private fun grilledPacificSaury(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GRILLED_PACIFIC_SAURY)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.GRILLED_PACIFIC_SAURY_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.8f).build())
    }

    private fun grilledSquid(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_GRILLED_SQUID)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.GRILLED_SQUID_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun hamburgSteak(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_HAMBURG_STEAK)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.HAMBURG_STEAK_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun japaneseRadish(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_JAPANESE_RADISH)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.JAPANESE_RADISH_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun kiwi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_KIWI)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.KIWI_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
    }

    private fun lettuce(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_LETTUCE)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SPRING.subSeasons(), *Season.FALL.subSeasons())
            .itemModel(CookingItemModels.LETTUCE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun lotusRoot(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_LOTUS_ROOT)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.LOTUS_ROOT_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun marinatedEggplant(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_MARINATED_EGGPLANT)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.MARINATED_EGGPLANT_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.5f).build())
    }

    private fun melon(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_MELON)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.MELON_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun miso(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_MISO)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(CookingItemModels.MISO_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun misoMackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_MISO_MACKEREL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.MISO_MACKEREL_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
    }

    private fun misoSoup(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_MISO_SOUP)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.MISO_SOUP_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun nappaCabbage(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_NAPPA_CABBAGE)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.NAPPA_CABBAGE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun nikujaga(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_NIKUJAGA)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.NIKUJAGA_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun oden(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_ODEN)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.ODEN_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun onion(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_ONION)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SPRING.subSeasons())
            .itemModel(CookingItemModels.ONION_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.3f).build())
    }

    private fun orange(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_ORANGE)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.ORANGE_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
    }

    private fun paperFan(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_PAPER_FAN)
            .category(ServerItemCategory.TOOL)
            .itemModel(CookingItemModels.PAPER_FAN_ITEM)
    }

    private fun parfait(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_PARFAIT)
            .category(ServerItemCategory.DESSERT)
            .itemModel(CookingItemModels.PARFAIT_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.6f).canAlwaysEat(true).build())
    }

    private fun peach(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_PEACH)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.PEACH_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
    }

    private fun persimmon(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_PERSIMMON)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.FALL.subSeasons())
            .itemModel(CookingItemModels.PERSIMMON_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
    }

    private fun pikeConger(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_PIKE_CONGER)
            .category(ServerItemCategory.FISH)
            .peakSeason(Season.SUMMER.withStage(Season.Stage.MID), Season.SUMMER.withStage(Season.Stage.LATE))
            .itemModel(CookingItemModels.PIKE_CONGER_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
    }

    private fun rice(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_RICE)
            .category(ServerItemCategory.MATERIAL)
            .peakSeason(*Season.FALL.subSeasons())
            .itemModel(CookingItemModels.RICE_ITEM)
            .food(FoodProperties.food().nutrition(1).saturation(0.2f).build())
    }

    private fun salad(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SALAD)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SALAD_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun salmonRoe(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SALMON_ROE)
            .category(ServerItemCategory.FISH)
            .peakSeason(Season.FALL.withStage(Season.Stage.MID), Season.FALL.withStage(Season.Stage.LATE))
            .itemModel(CookingItemModels.SALMON_ROE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
    }

    private fun salmonRoeSushi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SALMON_ROE_SUSHI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SALMON_ROE_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
    }

    private fun sardine(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SARDINE)
            .category(ServerItemCategory.FISH)
            .peakSeason(Season.SUMMER.withStage(Season.Stage.EARLY), Season.SUMMER.withStage(Season.Stage.MID))
            .itemModel(CookingItemModels.SARDINE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun sausage(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SAUSAGE)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SAUSAGE_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun seaUrchinSushi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SEA_URCHIN_SUSHI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SEA_URCHIN_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
    }

    private fun seafoodRiceFowl(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SEAFOOD_RICE_FOWL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SEAFOOD_RICE_FOWL_ITEM)
            .food(FoodProperties.food().nutrition(9).saturation(0.8f).build())
    }

    private fun shavedIce(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SHAVED_ICE)
            .category(ServerItemCategory.DESSERT)
            .itemModel(CookingItemModels.SHAVED_ICE_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build())
    }

    private fun skipjackTuna(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SKIPJACK_TUNA)
            .category(ServerItemCategory.FISH)
            .peakSeason(
                Season.SPRING.withStage(Season.Stage.EARLY),
                Season.SPRING.withStage(Season.Stage.MID),
                Season.FALL.withStage(Season.Stage.EARLY),
                Season.FALL.withStage(Season.Stage.MID)
            )
            .itemModel(CookingItemModels.SKIPJACK_TUNA_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun soba(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SOBA)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SOBA_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun softServeIceCream(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SOFT_SERVE_ICE_CREAM)
            .category(ServerItemCategory.DESSERT)
            .itemModel(CookingItemModels.SOFT_SERVE_ICE_CREAM_ITEM)
            .food(FoodProperties.food().nutrition(4).saturation(0.3f).canAlwaysEat(true).build())
    }

    private fun soybeans(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SOYBEANS)
            .category(ServerItemCategory.VEGETABLE)
            .itemModel(CookingItemModels.SOYBEANS_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun spinach(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SPINACH)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.WINTER.subSeasons())
            .itemModel(CookingItemModels.SPINACH_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.4f).build())
    }

    private fun squidSushi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SQUID_SUSHI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.SQUID_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
    }

    private fun steamedRice(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_STEAMED_RICE)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.STEAMED_RICE_ITEM)
            .food(FoodProperties.food().nutrition(5).saturation(0.6f).build())
    }

    private fun strawberry(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_STRAWBERRY)
            .category(ServerItemCategory.FRUIT)
            .peakSeason(*Season.SPRING.subSeasons())
            .itemModel(CookingItemModels.STRAWBERRY_ITEM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
    }

    private fun sweetPotato(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_SWEET_POTATO)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.FALL.subSeasons())
            .itemModel(CookingItemModels.SWEET_POTATO_ITEM)
            .food(FoodProperties.food().nutrition(3).saturation(0.6f).build())
    }

    private fun takoyaki(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TAKOYAKI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.TAKOYAKI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun tamagoSushi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TAMAGO_SUSHI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.TAMAGO_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.7f).build())
    }

    private fun teriyakiYellowtail(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TERIYAKI_YELLOWTAIL)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.TERIYAKI_YELLOWTAIL_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.8f).build())
    }

    private fun tomato(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TOMATO)
            .category(ServerItemCategory.VEGETABLE)
            .peakSeason(*Season.SUMMER.subSeasons())
            .itemModel(CookingItemModels.TOMATO)
            .food(FoodProperties.food().nutrition(3).saturation(0.4f).build())
    }

    private fun tonkatsu(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TONKATSU)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.TONKATSU_ITEM)
            .food(FoodProperties.food().nutrition(8).saturation(0.8f).build())
    }

    private fun tunaSushi(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_TUNA_SUSHI)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.TUNA_SUSHI_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
    }

    private fun udon(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_UDON)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.UDON_ITEM)
            .food(FoodProperties.food().nutrition(6).saturation(0.6f).build())
    }

    private fun yakisoba(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(CookingTranslations.ITEM_VANILIFE_YAKISOBA)
            .category(ServerItemCategory.FOOD)
            .itemModel(CookingItemModels.YAKISOBA_ITEM)
            .food(FoodProperties.food().nutrition(7).saturation(0.7f).build())
    }
}
