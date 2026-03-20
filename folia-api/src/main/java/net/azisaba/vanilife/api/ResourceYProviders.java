package net.azisaba.vanilife.api;

import org.jetbrains.annotations.Nullable;

public final class ResourceYProviders {
    private static ResourceYProvider provider = null;

    private ResourceYProviders() {}

    public static void register(ResourceYProvider p) {
        provider = p;
    }

    @Nullable
    public static ResourceYProvider get() {
        return provider;
    }
}
