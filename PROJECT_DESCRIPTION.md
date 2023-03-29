# Project overview

Described in this document is the abstract overview
of changes done by Folia. Folia splits the chunks within all loaded worlds
into independently ticking regions so that the regions are ticked
independently and in parallel. Described first will be intra region
operations, and then inter region operations.

## Rules for independent regions

In order to ensure that regions are independent, the rules for
maintaining regions must ensure that a ticking region
has no directly adjacent neighbour regions which are ticking.
The following rules guarantee the invariant is upheld:
1. Any ticking region may not grow while it is ticking.
2. Any ticking region must initially own a small buffer of chunks outside
   its perimeter.
3. Regions may not _begin_ to tick if they have a neighbouring adjacent
   region.
4. Adjacent regions must eventually merge to form a single region.

Additionally, to ensure that a region is not composed of independent regions
(which would hinder parallelism), regions composed of more than
one independent area must be eventually split into independent regions
when possible.

Finally, to ensure that ticking regions may store and maintain data
about the current region (i.e tick count, entities within the region, chunks
within the region, block/fluid tick lists, and more), regions have
their own data object that may only be accessed while ticking the region and
by the thread ticking the region. Also, there are callbacks to merging
or splitting regions so that the data object may be updated appropriately.

The implementation of these rules is described by [REGION_LOGIC.md](REGION_LOGIC.md).

The end result of applying these rules is that a ticking region can ensure that
only the current thread has write access to any data contained within the region,
and that at any given time the number of independent regions is close to maximum.

## Intra region operations

Intra region operations refer to any operations that only deal with data
for a single region by the owning region, or to merge/split logic.

### Ticking for independent regions

Independent regions tick independently and in parallel. To tick independently
means that regions maintain their own deadlines for scheduling the next tick. For
example, consider two regions A and B such that A's next tick start is at t=15ms
and B's next tick start is at t=0ms. Consider the following sequence of events:
1. At t = 0ms, B begins to tick.
2. At t = 15ms, A begins to tick.
3. At t = 20ms, B is finished its tick. It is then scheduled to tick again at t = 50ms.
4. At t = 50ms, B begins its 2nd tick.
5. At t = 70ms, B finishes its 2nd tick and is scheduled to tick again at t = 100ms.
6. At t = 95ms, A finishes its _first_ tick. It is scheduled to tick again at t = 95ms.

It is important to note that at no time was B's schedule affected by the fact that
A fell behind its 20TPS target.

To implement the described behavior, each region maintains a repeating
task on a scheduled executor (See SchedulerThreadPool) that schedules
tasks according to an earliest start time first scheduling algorithm. The
algorithm is similar to EDF, but schedules according to start time. However,
given that the deadline for each tick is 50ms + the start time, it behaves
identically to the EDF algorithm.

The EDF-like algorithm is selected so that as long as the thread pool is
not maximally utilised, that all regions that take <= 50ms to tick will
maintain 20TPS. However, the scheduling algorithm is neither NUMA aware
nor CPU core aware - it will not make attempts (when n regions > m threads)
to pin regions to certain cores.

Since regions tick independently, they maintain their own tick counters. The
implications of this are described in the next section.

### Tick counters

In standard Vanilla, there are several important tick counters: Current Tick,
Game Time Tick, and Daylight Time Tick. The Current Tick counter is used
for determining the tick number since the server has booted. The Game Time
Tick is maintained per world and is used to schedule block ticks
for redstone, fluids, and other physics events. The Daylight Time Tick
is simply the number of ticks since noon, maintained per world.

In Folia, the Current Tick is maintained per region. The Game Time Tick
is split into two counters: Redstone Time and Global Game Time.
Redstone Time is maintained per region. Global Game Time and
Daylight Time are maintained by the "global region."

At the start of each region tick, the global game time tick and
daylight time tick are copied from the global region and any time
the current region retrieves those values, it will retrieve from
the copy received at the start of tick. This is to ensure that
for any two calls to retrieve the tick number throughout the tick,
that those two calls report the same tick number.

The global game time is maintained for a couple of reasons:
1. There needs to be a counter representing how many ticks a world
   has existed for, since the game does track total number of days
   the world has gone on for.
2. Significant amounts of new entity AI code uses game time (for
   a reason I cannot divine) to store absolute deadlines of tasks.
   It is not impossible to write code to adjust the deadlines of
   all of these tasks, but the amount of work is significant.

#### Global region

The global region is a single scheduled task that is always scheduled
to run at 20TPS that is responsible for maintaining data that is not
tied to any specific region: game rules, global game time, daylight time,
console command handling, world border, weather, and others. Unlike the other
regions, the global region does not need to perform any special logic
for merging or splitting because it is never split or merged - there is
only one global region at any time. The global region does not own
any region specific data.

#### Merging and splitting region tick times

Since redstone and current ticks are maintained per region, there needs
to be appropriate logic to adjust the tick deadlines used by the block/fluid
tick scheduler and anything else that schedules by redstone/current
absolute tick time so that the relative deadline is unaffected.

When merging a region x (from) into a region y (into or to),
we can either adjust both the deadlines of x and y or just one of x and y.
It is simply easier to adjust one, and arbitrarily the region x is chosen.
Then, the deadlines of x must be adjusted so that considering the current
ticks of y that the relative deadlines remain unchanged.

Consider a deadline d1 = from tick + relative deadline in region x.
We then want the adjusted deadline d2 to be d2 = to tick + relative deadline
in region y, so that the relative tick deadline is maintained. We can
achieve this by applying an offset o to d1 so that d1 + o = d2, and the
offset used is o = tick to - tick from. This offset must be calculated
for redstone tick and current tick separately, since the logic to increase
redstone tick can be turned off by the Level#tickTime field.

Finally, the split case is easy - when a split occurs,
the independent regions from the split inherit the redstone/current tick
from the parent region. Thus, the relative deadlines are maintained as there
is no tick number change.

In all cases, redstone or any other events scheduled by current tick
remain unaffected when regions split or merge as the relative deadline
is maintained by applying an offset in the merge case and by copying
the tick number in the split case.

## Inter region operations

Inter region refer to operations that work with other regions that are not
the current ticking region that are in a completely unknown state. These
regions may be transient, may be ticking, or may not even exist.

### Utilities to assist operations

In order to assist in inter region operations, several utilities are provided.
In NMS, these utilities are the EntityScheduler, the RegionizedTaskQueue,
the global region task queue, and the region-local data provider
RegionizedData. The Folia API has similar analogues, but does not have
a region-local data provider as the NMS data provider holds critical
locks and is invoked in critical areas of code when performing any
callback logic and is thus highly susceptible to fatal plugin errors
involving lengthy I/O or world state modification.

#### EntityScheduler

The EntityScheduler allows tasks to be scheduled to be executed on the
region that owns the entity. This is particularly useful when dealing
with entity teleportation, as once an entity begins an asynchronous
teleport the entity cannot tick until the teleport has completed, and
the timing is undefined.

#### RegionizedTaskQueue

The RegionizedTaskQueue allows tasks to be scheduled to be executed on
the next tick of a region that owns a specific location, or creating
such region if it does not exist. This is useful for tasks that may
need to edit or retrieve world/block/chunk data outside the current region.

#### Global region task queue

The global region task queue is simply used to perform edits on data
that the global region owns, such as game rules, day time, weather,
or to execute commands using the console command sender.

#### RegionizedData

The RegionizedData class allows regions to define region-local data,
which allow regions to store data without having to consider concurrent
data access from other regions. For example, current per region
entity/chunk/block/fluid tick lists are maintained so that regions do not
need to consider concurrent access to these data sets.

<br></br>
The utilities allow various cross-region issues to be resolved in a
simple fashion, such as editing block/entity/world state from any region
by using tasks queues, or by avoiding concurrency issues by using
RegionizedData. More advanced operations such as teleportation,
player respawning, and portalling, all make use of these utilities
to ensure the operation is thread-safe.

### Entity intra and inter dimension teleports

Entities need special logic in order to teleport safely between
other regions or other dimensions. In all cases however, the call to
teleport/place an entity must be invoked on the region owning the entity.
The EntityScheduler can be used to easily schedule code to execute in such
a context.

#### Simple teleportation

In a simple teleportation, the entity already exists in a world at a location
and the target location and dimension are known.
This operation is split into two parts: transform and async place.
In this case, the transform operation removes the entity from the current
world, then adjusts the position. The async place operation schedules a task
to the target location using the RegionizedTaskQueue to add the entity to
the target dimension at the target position.

The various implementation details such as non-player entities being
copied in the transform operation are left out, as those are not relevant
for the high level overview.

Things such as player login and player respawn are generally
considered simple teleportation. The player login case only differs
since the player does not exist in any world at the start, and that the async
transform must additionally find a place to spawn the player.
The player respawn is similar to the player login as the respawn
differs by having the player in the world at the time of respawn.

#### Portal teleport

Portal teleport differs from simple teleportation as portalling does
_not_ know the exact location of the teleport. Thus, the transform step
does not update the entity position, but rather a new operation is inserted
between transform and async place: async search/create which is responsible
for finding and/or creating the exit portal.

Additionally, the current Vanilla code can refuse a portal if the
entity is non-player and the nether exit portal does not already exist. But
since the portal location is only determined by the async place, it is
too late to abort - so, portal logic has been re-done so that there is no
difference between players and entities. Now both entities and players
create exit portals, whether it be for the nether or end.

#### Shutdown during teleport

Since the teleport happens over multiple steps, the server shutdown
process must deal with uncompleted teleportations manually.

## Server shutdown process

The shutdown process occurs by spawning a separate shutdown thread,
which then runs the shutdown logic:
1. Shutdown the tick region scheduler, stopping any further ticks
2. Halt metrics processing
3. Disable plugins
4. Stop accepting new connections
5. Send disconnect (but do not remove) packets to all players
6. Halt the chunk systems for all worlds
7. Execute shutdown logic for all worlds by finish all pending teleports
   for all regions, then saving all chunks in the world, and finally
   saving the level data for the world (level.dat and other .dat files).
8. Save all players
9. Shutting down the resource manager
10. Releasing the level lock
11. Halting remaining executors (Util executor, region I/O threads, etc)


The important differences to Vanilla is that the player kick and
world saving logic is replaced by steps 5-8.

For step 5, the players cannot be kicked before teleportations are finished,
as kicking would save the player dat file. So, save is moved after.

For step 6, the chunk system halt is done before saving so that all chunk
generation is halted. This will reduce the load on the server as it shuts
down, which may be critical in memory-constrained scenarios.

For step 7, teleportations are completed differently depending on the type:
simple or portal.

Simple teleportations are completed by forcing
the entity being teleported to be added to the entity chunk specified
by the target location. This allows the entity to be saved at the target
position, as if the teleportation did complete before shutdown.

Portal teleportations are completed by forcing the entity being teleported
to be added to the entity chunk specified from where the entity
teleported _from_. Since the target location is not known, the entity
can only be placed back at the origin. While this behavior is not ideal,
the shutdown logic _must_ account for any broken world state - which means
that finding or create the target exit portal may not be an option.

The teleportation completion must be performed before the world save so that
the teleport completed entities save.

For step 8, only save players after the teleportations are completed.

The remaining steps are Vanilla.