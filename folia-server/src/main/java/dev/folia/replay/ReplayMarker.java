package dev.folia.replay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

public class ReplayMarker {

    public int time;
    public String name;
    public double x = 0;
    public double y = 0;
    public double z = 0;
    public float phi = 0;
    public float theta = 0;
    public float varphi = 0;

    public static class Serializer implements JsonSerializer<ReplayMarker> {
        @Override
        public JsonElement serialize(ReplayMarker src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject ret = new JsonObject();
            JsonObject value = new JsonObject();
            JsonObject position = new JsonObject();
            ret.add("realTimestamp", new JsonPrimitive(src.time));
            ret.add("value", value);

            value.add("name", new JsonPrimitive(src.name));
            value.add("position", position);

            position.add("x", new JsonPrimitive(src.x));
            position.add("y", new JsonPrimitive(src.y));
            position.add("z", new JsonPrimitive(src.z));
            position.add("yaw", new JsonPrimitive(src.phi));
            position.add("pitch", new JsonPrimitive(src.theta));
            position.add("roll", new JsonPrimitive(src.varphi));
            return ret;
        }
    }
}
