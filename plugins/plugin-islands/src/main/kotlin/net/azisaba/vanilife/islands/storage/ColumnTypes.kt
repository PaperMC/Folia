package net.azisaba.vanilife.islands.storage

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.postgresql.util.PGobject

fun Table.component(
    name: String,
    serializer: JSONComponentSerializer = JSONComponentSerializer.json()
): Column<Component> = registerColumn(name, ComponentColumnType(serializer))

private class ComponentColumnType(private val serializer: JSONComponentSerializer) : ColumnType<Component>() {
    override fun sqlType(): String = "jsonb"

    override fun valueFromDB(value: Any): Component? {
        val pgObject = value as PGobject
        val jsonStr = pgObject.value ?: return null
        return serializer.deserialize(jsonStr)
    }
    override fun notNullValueToDB(value: Component): Any = PGobject().apply {
        type = "jsonb"
        this.value = serializer.serialize(value)
    }
}
