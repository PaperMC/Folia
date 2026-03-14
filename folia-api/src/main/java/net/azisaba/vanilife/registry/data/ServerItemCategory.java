package net.azisaba.vanilife.registry.data;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.translation.Translatable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public enum ServerItemCategory implements Translatable {
    MATERIAL("item.vanilife.category.material", TextColor.color(255, 213, 79)),
    TOOL("item.vanilife.category.tool", TextColor.color(129, 212, 250)),
    EQUIPMENT("item.vanilife.category.equipment", TextColor.color(186, 104, 200)),

    VEGETABLE("item.vanilife.category.vegetable", TextColor.color(129, 199, 132)),
    FRUIT("item.vanilife.category.fruit", TextColor.color(244, 143, 177)),
    FISH("item.vanilife.category.fish", TextColor.color(100, 181, 246)),

    FOOD("item.vanilife.category.food", TextColor.color(255, 183, 77)),
    DRINK("item.vanilife.category.drink", TextColor.color(77, 208, 225)),
    DESSERT("item.vanilife.category.dessert", TextColor.color(255, 138, 128));

    private final String translationKey;
    private final TextColor color;

    ServerItemCategory(final String translationKey, final TextColor color) {
        this.translationKey = translationKey;
        this.color = color;
    }

    @Override
    public String translationKey() {
        return this.translationKey;
    }

    public TextColor color() {
        return this.color;
    }
}