package net.azisaba.vanilife.server.islands;

public record IslandsGeneratorSettings(
        int seaLevel,
        int seaDepth,
        int landTopY,
        int airTopY,
        int beachWidth
) {
}
