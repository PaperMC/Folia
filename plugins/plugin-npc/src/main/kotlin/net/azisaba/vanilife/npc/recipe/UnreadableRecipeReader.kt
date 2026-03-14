package net.azisaba.vanilife.npc.recipe

interface UnreadableRecipeReader {
    val readRecipes: Collection<UnreadableRecipe>

    fun readRecipe(recipe: UnreadableRecipe)

    fun canRead(recipe: UnreadableRecipe): Boolean
}
