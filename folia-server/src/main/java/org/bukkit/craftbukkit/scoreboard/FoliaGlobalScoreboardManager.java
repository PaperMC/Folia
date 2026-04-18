package org.bukkit.craftbukkit.scoreboard;

import io.papermc.paper.scoreboard.GlobalScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.NullMarked;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-specific implementation of the Global Scoreboard API.
 * Uses the Global Region Scheduler to ensure thread-safe updates.
 */
@NullMarked
public class FoliaGlobalScoreboardManager implements GlobalScoreboardManager {

    private final Scoreboard mainScoreboard;

    public FoliaGlobalScoreboardManager(Scoreboard mainScoreboard) {
        this.mainScoreboard = mainScoreboard;
    }

    @Override
    public Scoreboard getMainScoreboard() {
        return this.mainScoreboard;
    }

    @Override
    public CompletableFuture<Void> setScoreAsync(String entry, String objectiveName, int score) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Schedule the update to the Global Region (where the scoreboard logic resides)
        Bukkit.getGlobalRegionScheduler().run(null, (task) -> {
            try {
                this.mainScoreboard.getObjective(objectiveName).getScore(entry).setScore(score);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Integer> getScoreAsync(String entry, String objectiveName) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        // Safe lookup from the Global Region thread
        Bukkit.getGlobalRegionScheduler().run(null, (task) -> {
            try {
                int score = this.mainScoreboard.getObjective(objectiveName).getScore(entry).getScore();
                future.complete(score);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
