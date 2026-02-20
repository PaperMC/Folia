package dev.folia.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.util.UUID;

public class UUIDSerializer implements JsonSerializer<UUID> {
    @Override
    public JsonElement serialize(@NotNull UUID src, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(src.toString());
    }
}
