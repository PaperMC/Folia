package net.azisaba.vanilife.registry.data;

import net.kyori.adventure.translation.Translatable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public enum ServerItemCategory implements Translatable {
    MATERIAL("item.vanilife.category.material"),
    TOOL("item.vanilife.category.tool"),
    EQUIPMENT("item.vanilife.category.equipment"),
    VEGETABLE("item.vanilife.category.vegetable"),
    FRUIT("item.vanilife.category.fruit"),
    FISH("item.vanilife.category.fish"),
    FOOD("item.vanilife.category.food"),
    DRINK("item.vanilife.category.drink"),
    DESSERT("item.vanilife.category.dessert");

    private final String translationKey;

    ServerItemCategory(final String translationKey) {
        this.translationKey = translationKey;
    }

    @Override
    public String translationKey() {
        return this.translationKey;
    }
}
