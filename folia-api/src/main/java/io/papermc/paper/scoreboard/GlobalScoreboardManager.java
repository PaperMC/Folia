package io.papermc.paper.scoreboard;

import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.NullMarked;
import java.util.concurrent.CompletableFuture;

/**
 * Manages scoreboard updates across different Folia regions.
 * This API allows thread-safe interactions with the global scoreboard.
 */
@NullMarked
public interface GlobalScoreboardManager {

    /**
     * Gets the main scoreboard shared across all regions.
     * Note: Direct modifications to this scoreboard may not be thread-safe.
     * Use async methods for safe updates.
     *
     * @return the main scoreboard
     */
    Scoreboard getMainScoreboard();

    /**
     * Safely updates a score for an entry across all regions.
     *
     * @param entry the entry to update (e.g., player name)
     * @param objectiveName the name of the objective
     * @param score the new score value
     * @return a future that completes when the update is synchronized globally
     */
    CompletableFuture<Void> setScoreAsync(String entry, String objectiveName, int score);

    /**
     * Safely retrieves a score from the global scoreboard.
     *
     * @param entry the entry
     * @param objectiveName the objective
     * @return a future containing the current score
     */
    CompletableFuture<Integer> getScoreAsync(String entry, String objectiveName);
}
