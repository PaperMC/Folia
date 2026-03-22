# Resource World Auto-Wipe — How It Works

This document explains the design and runtime behaviour of the Resource World Auto-Wipe system. It describes when wipes run, what happens before/during/after a wipe, configuration examples, commands, safety behaviour, backups, and recovery.

## Summary

- Purpose: Automatically reset (wipe) configurable resource worlds on a schedule or on-demand, while taking backups and ensuring player safety.
- Default strategy: reset-by-template (copy a prepared template world into the resource world folder). The implementation also supports other strategies (e.g., chunk-regeneration) if configured.
- Backups: pre-wipe compressed snapshots with retention/rotation.

## Core concepts

- Resource world: a world configured to be periodically wiped (e.g. "mining", "islands", "resource_world").
- Template world: a pristine world folder used as the source for resets. Kept under the plugin data folder or a configured path.
- Backup snapshot: a compressed archive of the current world created before a wipe.
- Scheduler: drives automatic wipes based on human-friendly intervals or cron expressions.
- WorldManager: component that performs the unload, copy, and reload operations safely (Folia-aware when running on Folia).

## When wipes run

Wipes occur under these triggers (configurable):

- Scheduled automatic wipe: run at configured intervals or cron expression (recommended for predictable resets).
- Manual wipe: executed immediately via admin command (`/resourcewipe force <world>`).
- On server start: optionally run a wipe on server startup if enabled in config.
- Conditional triggers: optional plugin hooks for external criteria (player count, world size) — available via plugin API.

Typical schedule examples:

- Daily at midnight: `0 0 * * *` (cron) or `every 1d at 00:00` (human-friendly form)
- Weekly on Monday at 04:00: `0 4 * * 1` or `every 7d at 04:00` (depending on config parser)

## High-level wipe workflow

1. Announcement phase
   - When a scheduled wipe is imminent, the system broadcasts configurable countdown messages (action bar/title/chat) at configurable intervals (e.g., 10m, 5m, 1m, 10s).
2. Prepare players
   - Players currently inside the target world will be teleported to a safe world or spawn (configurable), or optionally moved to spectator mode until the wipe completes.
   - Optionally preserve and store player inventories if configured.
3. Pause world access
   - The world is locked to prevent new joins while the wipe proceeds.
4. Create backup (optional but recommended)
   - The BackupManager creates a compressed snapshot (zip) of the world folder and stores it under `plugins/resource-wipe/backups/<worldname>/<timestamp>.zip`.
   - Rotation: only the last `N` snapshots are kept (configurable; default: 7). Older snapshots are removed.
5. Unload world
   - The plugin uses server APIs and respects Folia dispatchers to safely unload the world.
6. Reset world
   - Template-copy strategy (default): move or rename the existing world folder out of the active folder and copy the template world folder into place.
   - Alternative strategies (if enabled): chunk regeneration using server APIs to change blocks back to template state.
7. Load world
   - Load the fresh world and restore spawn, world settings, and any configured metadata.
8. Post-wipe steps
   - Run optional post-wipe script or hook.
   - Re-allow player access and announce completion.

## Configuration (example)

Place configuration under `plugins/resource-wipe/config.yml` with keys similar to the example below.

```yaml
# Example config snippet
resourceWorlds:
  - name: resource_world
    templatePath: templates/resource_world
    wipeSchedule: "0 4 * * *" # cron expression (daily at 04:00)
    backup: true
    retention: 7
    preserveInventories: false
    teleportTarget: lobby
announce:
  preWipeSeconds: [600,300,60,10]
  messages:
    - "Resource world will wipe in {time}. Save your stuff!"
wipe:
  strategy: template-copy # or chunk-regen
  dryRun: false
safety:
  safeWorld: hub
  teleportPlayers: true
  lockDuringWipe: true
```

Notes:
- `templatePath` can be relative to the plugin data folder or an absolute path.
- `wipeSchedule` supports cron expressions and human-friendly intervals when the parser is enabled.

## Commands & permissions

- `/resourcewipe status [world]` — show next scheduled wipe and last wipe details. Permission: `vanilife.resourcewipe.status` (or read default).
- `/resourcewipe force <world>` — force immediate wipe (requires confirmation or `--force`). Permission: `vanilife.resourcewipe.admin`.
- `/resourcewipe backup <world>` — create a backup now. Permission: `vanilife.resourcewipe.admin`.
- `/resourcewipe pause <world>` — pause scheduled wipes for the world. Permission: `vanilife.resourcewipe.admin`.
- `/resourcewipe resume <world>` — resume scheduled wipes. Permission: `vanilife.resourcewipe.admin`.

Admin command examples:
- ` /resourcewipe force resource_world --force`
- ` /resourcewipe status resource_world`

## Safety considerations

- Always enable backups (default) to avoid accidental data loss.
- Use the template-copy strategy for reliability and speed; ensure template is prepared and tested.
- When running on Folia, the plugin uses region/world dispatchers and unload/load APIs to avoid cross-thread operations.
- Use announcements and a configurable grace period to give players time to prepare.
- Validate free disk space before performing backups or copy operations to avoid partial wipes.

## Failure modes & recovery

- Backup creation failed: the plugin will abort the wipe and re-open the world to players, and log an error. Admin notification will be broadcast.
- Copy or filesystem error during reset: the plugin will attempt to roll back by restoring the moved world folder (if present) and loading it back; it will then notify admins and keep the world available.
- Partial load failure: plugin logs error and does not mark wipe as successful; backup remains intact for manual recovery.

Manual recovery steps (if something goes wrong):
1. Stop the server.
2. Restore a backup manually by unzipping `plugins/resource-wipe/backups/<world>/<timestamp>.zip` into the server root and renaming to the world folder name.
3. Start the server and verify.

## Storage & retention

- Backups default to `plugins/resource-wipe/backups` and are compressed to save space.
- Default retention: keep the most recent 7 backups per world; older files are deleted automatically.
- For larger deployments consider offloading backups to remote storage (S3) via custom post-backup hook or an integration; this is not enabled by default.

## Notifications & logs

- The plugin logs operations to the server log at INFO level; failures are logged at ERROR.
- Admins may opt-in for more verbose debug logs in `config.yml`.
- Broadcast messages are configurable and support placeholders (e.g., `{world}`, `{time}`, `{remaining}`).

## Recommended defaults

- Strategy: `template-copy`
- Backups: enabled, retention 7
- Pre-wipe announcements: `[600,300,60,10]` seconds
- Teleport players to a safe world named `hub` or `lobby`

## FAQ

Q: Can players keep their inventories across wipes?
A: Yes — enable `preserveInventories: true` in the world config. The plugin will save inventories and attempt to restore them after the wipe for safety. This can be storage/time intensive.

Q: How long does a wipe take?
A: Depends on world size and storage speed. A template-copy for a few hundred MB may take seconds to a minute; backups add time proportional to world size and compression settings.

Q: Can I test a wipe without affecting players?
A: Use `dryRun: true` to simulate the wipe process (no filesystem changes). You can also run backups only with `/resourcewipe backup <world>`.

## Where to find the docs

- This file: `docs/resource-wipe.md`
- Plugin data folder runtime examples: `plugins/resource-wipe/config.yml` and `plugins/resource-wipe/backups`

---

If you want, I can now:
- Create an initial `plugins/plugin-resourcewipe` skeleton with `plugin.yml` and `config.yml` example.
- Implement the BackupManager or WorldManager next.

Which would you like me to do next?