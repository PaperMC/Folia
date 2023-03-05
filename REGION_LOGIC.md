## Fundamental regionising logic

## Region

A region is simply a set of owned chunk positions and implementation 
defined unique data object tied to that region. It is important
to note that for any non-dead region x, that for each chunk position y
it owns that there is no other non-dead region z such that
the region z owns the chunk position y.

## Regioniser

Each world has its own regioniser. The regioniser is a term used 
to describe the logic that the class "ThreadedRegioniser" executes 
to create, maintain, and destroy regions. Maintenance of regions is 
done by merging nearby regions together, marking which regions 
are eligible to be ticked, and finally by splitting any regions 
into smaller independent regions. Effectively, it is the logic 
performed to ensure that groups of nearby chunks are considered 
a single independent region.

## Guarantees the regioniser provides

The regioniser provides a set of important invariants that allows
regions to tick in parallel without race condtions:

### First invariant

The first invariant is simply that any chunk holder that exists
has one, and only one, corresponding region.

### Second invariant

The second invariant is that for every _existing_ chunk holder x that is 
contained in a region that every each chunk position within the 
"merge radius" of x is owned by the region. Effectively, this invariant 
guarantees that the region is not close to another region, which allows
the region to assume while ticking it can create data for chunk holders
"close" to it. 

### Third invariant

The third invariant is that a ticking region _cannot_ expand
the chunk positions it owns as it ticks. The third invariant
is important as it prevents ticking regions from "fighting"
over non-owned nearby chunks, to ensure that they truly tick
in parallel, no matter what chunk loads they may issue while
ticking. 

To comply with the first invariant, the regioniser will 
create "transient" regions _around_ ticking regions. Specifically, 
around in this context means close enough that would require a merge, 
but not far enough to be considered independent. The transient regions
created in these cases will be merged into the ticking region 
when the ticking region finishes ticking.

Both of the second invariant and third invariant combined allow 
the regioniser to guarantee that a ticking region may create
and then access chunk holders around it (i.e sync loading) without
the possibility that it steps on another region's toes.

### Fourth invariant

The fourth invariant is that a region is only in one of four
states: "transient", "ready", "ticking", or "dead." 

The "ready" state allows a state to transition to the "ticking" state,
while the "transient" state is used as a state for a region that may
not tick. The "dead" state is used to mark regions which should
not be use.

The states transistions are explained later, as it ties in
with the regioniser's merge and split logic.

## Regioniser implementation

The regioniser implementation is a description of how
the class "ThreadedRegioniser" adheres to the four invariants
described previously.

### Splitting the world into sections

The regioniser does not operate on chunk coordinates, but rather
on "region section coordinates." Region section coordinates simply
represent a grouping of NxN chunks on a grid, where N is some power
of two. The actual number is left ambiguous, as region section coordinates
are only an internal detail of how chunks are grouped. 
For example, with N=16 the region section (0,0) encompasses all 
chunks x in [0,15] and z in [0,15]. This concept is similar to how 
the chunk coordinate (0,0) encompasses all blocks x in [0, 15] 
and z in [0, 15]. Another example with N=16, the chunk (17, -5) is 
contained within region section (1, -1). 

Region section coordinates are used only as a performance
tradeoff in the regioniser, as by approximating chunks to their
region coordinate allows it to treat NxN chunks as a single
unit for regionising. This means that regions do not own chunks positions,
but rather own region section positions. The grouping of NxN chunks 
allows the regionising logic to be performed only on 
the creation/destruction of region sections.
For example with N=16 this means up to NxN-1=255 possible
less operations in areas such as addChunk/region recalculation 
assuming region sections are always full.

### Implementation variables

The implemnetation variables control how aggressively the
regioniser will maintain regions and merge regions.

#### Recalculation count

The recalculation count is the minimum number of region sections
that a region must own to allow it to re-calculate. Note that
a recalculation operation simply calculates the set of independent
regions that exist within a region to check if a split can be
performed. 
This is a simple performance knob that allows split logic to be 
turned off for small regions, as it is unlikely that small regions 
can be split in the first place.

#### Max dead section percent

The max dead section percent is the minimum percent of dead 
sections in a region that must exist before a region can run
re-calculation logic.

#### Empty section creation radius

The empty section creation radius variable is used to determine
how many empty region sections are to exist around _any_ 
region section with at least one chunk. 

Internally, the regioniser enforces the third invariant by
preventing ticking regions from owning new region sections.
The creation of empty sections around any non-empty section will 
then enforce the second invariant.

#### Region section merge radius

The merge radius variable is used to ensure that for any
existing region section x that for any other region section y within
the merge radius are either owned by region that owns x 
or are pending a merge into the region that owns x or that the
region that owns x is pending a merge into the region that owns y.

#### Region section chunk shift

The region section chunk shift is simply log2(grid size N). Thus,
N = 1 << region section chunk shift. The conversion from
chunk position to region section is additionally defined as
region coordinate = chunk coordinate >> region section chunk shift.

### Operation

The regioniser is operated by invoking ThreadedRegioniser#addChunk(x, z) 
or ThreadedRegioniser#removeChunk(x, z) when a chunk holder is created 
or destroyed. 

Additionally, ThreadedRegion#tryMarkTicking can be used by a caller
that attempts to move a region from the "ready" state to the "ticking"
state. It is vital to note that this function will return false if 
the region is not in the "ready" state, as it is possible
that even a region considered to be "ready" in the past (i.e scheduled
to tick) may be unexpectedly marked as "transient." Thus, the caller
needs to handle such cases. The caller that successfully marks 
a region as ticking must mark it as non-ticking by using
ThreadedRegion#markNotTicking.

The function ThreadedRegion#markNotTicking returns true if the
region was migrated from "ticking" state to "ready" state, and false
in all other cases. Effectively, it returns whether the current region
may be later ticked again.

### Region section state

A region section state is one of "dead" or "alive." A region section
may additionally be considered "non-empty" if it contains
at least one chunk position, and "empty" otherwise.

A region section is considered "dead" if and only if the region section
is also "empty" and that there exist no other "empty" sections within the 
empty section creation radius. 

The existence of the dead section state is purely for performance, as it
allows the recalculation logic of a region to be delayed until the region
contains enough dead sections. However, dead sections are still 
considered to belong to the region that owns them just as alive sections.

### Addition of chunks (addChunk)

The addition of chunks to the regioniser boils down to two cases:

#### Target region section already exists and is not empty

In this case, it simply adds the chunk to the section and returns.

#### Target region section does not exist or is empty

In this case, the region section will be created if it does not exist.
Additionally, the region sections in the "create empty radius" will be
created as well.

Then, any region in the create empty radius + merge radius are collected
into a set X. This set represents the regions that need to be merged
later to adhere to the second invariant.

If the set X contains no elements, then a region is created in the ready
state to own all of the created sections.

If the set X contains just 1 region, then no regions need to be merged
and no region state is modified, and the sections are added to this
1 region.

Merge logic needs to occur when there are more than 1 region in the
set X. From the set X, a region x is selected that is not ticking. If
no such x exists, then a region x is created. Every region section 
created is added to the set x, as it is the section that is known
to not be ticking - this is done to adhere to invariant third invariant.

Every region y in the set X that is not x is merged into x if
y is not in the ticking state, otherwise x runs the merge later
logic into y.

### Merge later logic

A merge later operation may only take place from 
a non-ticking, non-dead region x into a ticking region y.
The merge later logic relies on maintaining a set of regions
to merge into later per region, and another set of regions
that are expected to merge into this region.
Effectively, a merge into later operation from x into y will add y into x's
merge into later set, and add x into y's expecting merge from set.

When the ticking region finishes ticking, the ticking region 
will perform the merge logic for all expecting merges.

### Merge logic

A merge operation may only take place between a dead region x
and another region y which may be either "transient" 
or "ready." The region x is effectively absorbed into the
region y, as the sections in x are moved to the region y.

The merge into later is also forwarded to the region y, 
such so that the regions x was to merge into later, y will
now merge into later. 

Additionally, if there is implementation specific data
on region x, the region callback to merge the data into the
region y is invoked.

The state of the region y may be updated after a merge operation
completes. For example, if the region x was "transient", then
the region y should be downgraded to transient as well. Specifically,
the region y should be marked as transient if region x contained 
merge later targets that were not y. The downgrading to transient is 
required to adhere to the second invariant.

### Removal of chunks (removeChunk)

Removal of chunks from region sections simple updates
the region sections state to "dead" or "alive", as well as the
region sections in the empty creation radius. It will not update
any region state, and nor will it purge region sections.

### Region tick start (tryMarkTicking)

The tick start simply migrates the state to ticking, so that
invariants #2 and #3 can be met. 

### Region tick end (markNotTicking)

At the end of a tick, the region's new state is not immediately known.

First, tt first must process its pending merges. 

After it processes its pending merges, it must then check if the 
region is now pending merge into any other region. If it is, then 
it transitions to the transient state. 

Otherwise, it will process the removal of dead sections and attempt 
to split into smaller regions. Note that it is guaranteed 
that if a region can be possibly split, it must remove dead sections,
otherwise, this would contradict the rules used to build the region 
in the first place.
