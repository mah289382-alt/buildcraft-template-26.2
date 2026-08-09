# BuildCraft NeoForge 26.2 Port — Remaining Features Explained

Companion doc to `PORT_CHECKLIST.md` (the terse done/not-done table). This one explains, in real
depth, **what each not-yet-built feature actually does** in the original 1.12.2 source — so you
can pick what's worth porting without having to go read the original Java yourself. Written by
reading the real classes in `reference/buildcraft-source/common/buildcraft/`, not from general
BuildCraft folklore (behavior differs across BC versions).

Lives outside `src/main/java` — pure documentation, not compiled.

---

## builders

### ~~1. Filler~~ — DECLINED, not being built
Mass-fills or clears a cuboid region using a chosen geometric pattern, drawing blocks from its own
27-slot internal inventory rather than the world. It needs an adjacent Volume Box (or the older
marker system) defining the region — right-click opens a GUI where you pick a pattern (Fill,
Clear/excavate, hollow Box, Frame, Pyramid, Stairs, full/half/quarter/eighth Sphere, and a family
of extruded 2D shapes — triangle, square, pentagon, hexagon, octagon, arc, semicircle, circle),
each with its own parameters (axis, rotation, hollow, centered, facing) plus an invert toggle.
Internally the pattern becomes a lightweight Template, fed through the same builder machinery the
Builder block uses (below), consuming MJ power and matching blocks from its inventory each tick
(with an option to also pick up broken blocks when clearing). Supports being turned on/off/looped
by redstone or a gate, same as an engine.

Depends on: the Volume Box/marker area-selection system (already have a single merged Marker
block; would need the Volume Box item + resize-by-dragging interaction to fully match source) and
the shared build-engine plumbing the Builder needs anyway.

### ~~2. Builder~~ — DECLINED, not being built
Constructs a saved Blueprint or Template (see Snapshot item, below) in the world block-by-block,
consuming MJ power plus items/fluids from its own inventory (27 item slots, 4 fluid tanks). You
load a "used" Snapshot item into its input slot; it looks up the actual saved structure data and
places it relative to whatever's behind the Builder (matching its facing to the block's facing).
A neat extra: if a chain of Path Markers is behind it instead of a single point, the Builder will
build repeated copies of the blueprint moving along that path, advancing to the next spot each
time the current copy finishes — e.g. stamping the same section of track/wall repeatedly down a
line. This is the actual "print the base" machine at the end of the builders chain.

Depends on: the Snapshot item + world-saved-data snapshot storage system, and ideally the Path
Marker feature for the repeat-along-a-line behavior (optional — single-build mode doesn't need it).

### ~~3. Architect Table~~ — DECLINED, not being built
The "scanner" — reads an in-world region into a new Snapshot item (Blueprint or Template). Needs
an adjacent Volume Box/marker defining what to scan. Insert a blank Snapshot item, and each tick it
scans a batch of blocks (Templates just record "occupied or not" per cell and scan fast; Blueprints
record full block state + NBT via a de-duplicated block palette, plus a second pass to capture
entities like item frames/paintings). A green scanning-line effect shows progress client-side. When
done it stores the actual data in a per-world save-data table (keyed by content hash) and hands
back a small "used" Snapshot item that just references that key — so the item itself stays tiny no
matter how big the structure was. This is the entry point of the whole builders chain.

Depends on: same area-selection system as Filler/Builder, plus the Snapshot/save-data
infrastructure.

### ~~4. Electronic Library~~ — DECLINED, not being built
A "download/upload" hub for making a Snapshot's data fully self-contained on the item (instead of
just a reference into this world's save-data) — useful for moving a blueprint to a different
world/server, or archiving it outside the save file. Has two independent transfer pairs: "down"
(used snapshot in → self-contained, NBT-compressed snapshot item out, ~2.5s) and "up" (a
self-contained snapshot in → streams its data server-side and registers it into the current
world's save-data, handing back a normal used-snapshot item referencing the new key). It's the
distribution/archival node, complementing Architect Table (creates) and Builder (consumes).

Depends on: the same Snapshot/save-data system as the two above — this is really "phase 2" of that
system, not something to build before it.

### ~~5. Replacer~~ — DECLINED, not being built
Doesn't touch the world at all — it edits a Blueprint's *data* to swap one block type for another
throughout, producing a new blueprint. Three input slots: a used Blueprint Snapshot, and two
Schematic Single items (below) representing the "from" and "to" block. Fill all three and it
instantly computes a new blueprint with every matching palette entry swapped, registers it, and
outputs a new Snapshot item. Handy for "I scanned an oak-plank house, now I want a spruce version"
without re-scanning or rebuilding by hand. Only works on Blueprints (Templates have no per-block
data to swap).

Depends on: Snapshot system + Schematic Single item.

### ~~6. Snapshot item~~ — DECLINED, not being built
The physical item form of a Blueprint or Template. 4 states: Template-clean, Template-used,
Blueprint-clean, Blueprint-used. "Clean" ones are blank and stackable; "used" ones are single-stack
and just carry a small header (content hash, owner, date, player-given name) pointing at the real
data in world save-data. Templates store *only shape* (which cells are occupied — used by Filler's
box/frame/etc. patterns and quick structural stamps). Blueprints store full fidelity: a
de-duplicated palette of exact block states + NBT, plus recorded entities. This is the core data
type the whole builders module revolves around — Architect Table produces it, Replacer edits it,
Builder consumes it, Electronic Library archives it.

### ~~7. Schematic Single item~~ — DECLINED, not being built
Captures a *single block's* exact state+NBT (not a whole structure) as a portable "stamp." Clean
state is empty; right-click a block to capture it (used state, stack size 1); right-click elsewhere
to try to place an exact copy, provided your inventory has the matching materials (fails cleanly if
it needs fluids, since a hand-item build can't supply those). Its main real use in the automation
chain is as the From/To input pair for the Replacer above, but it's independently useful as a
"clone this specific configured block" tool.

### ~~8. Filler Planner item~~ — DECLINED, not being built
Not a normal held tool — it's an "addon" you attach directly to a Volume Box (via right-click on
the box) instead of needing a physical Filler machine present. Once attached, right-clicking it
opens the same pattern-selection GUI a real Filler uses, letting you configure a shape directly on
the marker. A Filler block placed adjacent to that box later inherits the addon's pattern instead
of needing its own configured — decouples "what shape to build" (the marker+addon) from "which
machine executes it" (the physical Filler).

Depends on: Volume Box's addon-slot system + Filler's pattern GUI (build Filler first, this is a
natural follow-on).

---

## core

### ~~9. Gear items (Wood/Stone/Iron/Gold/Diamond)~~ — BUILT 2026-08-02
Purely a tiered crafting ingredient — no in-world behavior, no tool logic, no right-click handler
at all. Each tier crafts from a diamond-shaped pattern (item in center, 4 pieces around) using the
previous gear tier plus one raw material: Wood gear from sticks, Stone gear = Wood gear +
cobblestone, Iron gear = Stone gear + iron ingot, Gold gear = Iron gear + gold ingot, Diamond gear
= Gold gear + diamond. Real items/textures/recipes built, and rewired into the 8 real recipes that
use them in this port (Quarry, Mining Well, Pump, Wrench, all 3 built Engine tiers, Stripes pipe) -
these previously all substituted a plain vanilla ingot instead, per the earlier recipe audit.

### ~~10. Decorated block~~ — DECLINED, not being built
A single block with several purely cosmetic variants (destroy marker, blueprint, laser_back,
leather, paper, template), no tile entity, no interaction logic — behaves like a vanilla decorative
block. Only one variant (`laser_back`) has an enabled recipe in this source snapshot (obsidian ring
+ redstone block core) — it's meant as a mounting-plate backdrop for laser-beam visual effects, not
a general player-placeable decoration. Low priority: zero mechanical function beyond light level
and appearance.

### ~~11. Paintbrush item~~ — DECLINED, not being built
Recolors paintable blocks (pipes, anything implementing BuildCraft's colorable-block API) via
right-click, using a dye-loaded charge system (64 uses per charge) instead of needing a fresh dye
item every time — 16 dye-color metadata variants plus a "clean"/uncharged state. Spawns colored
particles and a sound on successful paint; reverts to clean once exhausted. Purely a hand tool, no
machine counterpart — the manual complement to any automated pipe-coloring mechanism.

### ~~12. List item~~ — DECLINED, not being built (only real consumer is the Gate system, which isn't built)
A configuration item implementing BuildCraft's generic item-filter API (`IList`), referenced by
other machines (sorting pipes, filtered chests, etc.) wherever "does this item match my filter?"
logic is needed. Right-click in the air opens a 9×2 grid GUI where each of 9 "lines" holds up to 9
example items plus three toggles: precise (match exact damage/NBT), byType (match by item
category using slot 0 as an example), byMaterial (broader category match, e.g. "any wood plank").
You configure it once, then insert it into a consuming machine as a reusable, named filter.

### ~~13. Map Location item~~ — DECLINED, not being built
A general "capture a position/region" item with 6 sub-types: Spot (a single point+side), Area (min/
max corners, captured from a completed marker), Path / Path-Repeating (an ordered list of points
from a chain of Path Markers, auto-detected as repeating/looped if the path closes on itself), and
Zone (tied to the Robotics module's Zone Planner). Right-click a marker/area-provider tile to
capture its data onto the item; shift-right-click to clear it back to blank. Used to feed
area/path/point data into other machines' GUIs without needing physical markers still present at
that machine.

### ~~14. Marker Connector item~~ — DECLINED, not being built (both its jobs serve declined features)

The interaction tool for the whole marker/volume system — doesn't place blocks itself. Two jobs:
(1) scans nearby markers along your line of sight and links the best pair into a connected
path/line (the classic point-to-point marker system); (2) manages Volume Boxes — right-click a
box's corner to start/continue an interactive corner-drag resize, shift-right-click a box to
delete it, right-click one of its addon slots (like the Filler Planner) to open that addon's GUI,
shift-right-click to remove the addon. This is the single tool tying "define a region/line" to
"let a machine build/scan/fill/traverse it."

### ~~15. Volume Box item~~ — DECLINED, not being built
*Its only real consumers upstream (Filler, Architect Table) were already declined too.*

Creates a brand-new Volume Box — right-click a block face to place a zero-size box adjacent to it
(no physical block/tile spawned, it's pure save-data plus a client-rendered outline), then use the
Marker Connector to drag its corner and size it into an actual cuboid. This is the modern
replacement for the old physical-marker-block area system, and it's the shared "region" backbone
that Filler, Architect Table, and (indirectly) Builder all attach to.

### ~~16. Fragile Fluid Shard item~~ — DECLINED, not being built
Not player-craftable — auto-generated when a BuildCraft fluid block "shatters" into item form
(e.g. mined without the right tool). Holds up to 500mB, drain-only (can never be refilled), meant
to be emptied once rather than reused like a bucket. Mostly a minor lore/consolation-prize mechanic
so breaking a fluid block by hand isn't a complete waste — not something pipes/tanks depend on.

---

## energy

### ~~MJ Dynamo~~ — DECLINED 2026-08-03 (electricity-system redesign planned around a future
IndustrialCraft port; out of scope until then)
Converts stored MJ *into* RF, letting your MJ power network drive RF-based machines from other
mods. Holds an internal MJ battery (fed by engines/pipes on 5 of its 6 faces) and a small RF
buffer drained out the 6th (wrench-rotatable, can chain through up to 3 more dynamos to reach a
receiver further away). Needs a redstone signal to run, same gating as the classic engines. Has
the same heat/overheat visual stages as an engine (explosion-on-overheat is stubbed out/TODO even
in the original). Off by default in source config. Only useful if you want two-way bridging
between this mod's MJ economy and RF machines from other mods — with none of those present, low
priority.

### ~~RF Engine tier~~ — DECLINED 2026-08-03 (same electricity-redesign reason as MJ Dynamo above)

A 4th engine block built on the same heat/piston/redstone-gating framework as
Redstone/Stirling/Combustion, but instead of burning fuel it does the reverse of the Dynamo: it's
a one-way RF *sink* — accepts RF from a cable plugged into any side, converts it to MJ each tick at
the same global conversion rate the Dynamo uses, and pushes that MJ out its own facing side using
the normal engine power-push logic. Visually/mechanically behaves exactly like a Stone/Iron engine.
Off by default in source config, same RF-bridging use case as the Dynamo.

### Glob of Oil item
Effectively dead code in this version of the source — registered, textured, but with **no recipe,
no drop table, and no code anywhere that gives, consumes, or checks for it**. Not a real feature to
port; this port's real Oil fluid+bucket already covers oil's actual gameplay role.

---

## factory

### ~~Auto Workbench~~ — BUILT 2026-08-02, user-confirmed working in-game
A "ghost recipe" auto-crafter. Has a 3×3 phantom blueprint grid (place an example item, it's
never consumed), a filtered materials inventory that pipes feed (filter auto-derived from the
blueprint), and a result slot, plus a live preview of what the current pattern will craft. Each
tick it re-checks for a matching vanilla recipe and, once it has an internal FE charge built up
(chargeable slowly on its own even with zero external power - 10 real seconds unpowered, matching
source's exact ratio - or much faster from an Engine), executes the craft and inserts the real
output.

**Real improvement over source, not just a port**: real source only ever shipped exact-stack
matching (a fuzzy "any tag-matching ingredient" path exists in source but is commented out/never
finished) - this port finished that unshipped path, so materials genuinely accept any item
satisfying the matched recipe's real `Ingredient` (any plank species, any wool color, etc.), not
just the one exact item type used to build the blueprint.

**Real bugs found and fixed during implementation** (documented in project memory): the GUI
result-preview never synced to the client (fixed by switching to a real, normally-synced Slot,
matching how vanilla's own `CraftingMenu` does it, instead of trying to sync a plain block-entity
field); the output slot's `isValid` was accidentally blocking the machine's OWN internal insert of
its crafted output, not just external pipes (permanently blocked all crafting - found via live log
data showing materials correctly present but `canCraft` stuck false); a `RefineryBlock`/
`EngineBlock`-shared client/server prediction bug (ghost-placing a held item when right-clicking to
open the GUI) was also found and fixed across all three blocks while investigating this one.

### ~~Flood Gate~~ — DECLINED as a standalone block; folding into the Pump instead
*User decision 2026-08-02: instead of a separate Flood Gate block, add a wrench-toggleable
Drain/Fill mode to the existing Pump, reusing its proven BFS flood-fill traversal
(`PumpBlockEntity.buildQueueAt`) in reverse — Fill mode drains the Pump's own tank into found
empty/connected positions instead of filling the tank from found fluid. Not yet implemented.*

No GUI — wrench-click a face to toggle it "open." Once it holds fluid (fed by pipes/pumps into a
small internal tank), every 16 ticks it does a real breadth-first flood-fill search outward through
its open faces (up to 64 blocks / 4096 positions) for reachable empty/matching-fluid positions,
verifies the path back is still clear, and places a source block there via a fake-player (so other
mods' placement-protection systems see a legitimate "player" placing it, not the block cheating
past permissions). This genuinely fills an entire connected basin over time, not just "spawn one
block" — point it at an oil lake or a basin, open the right sides, keep it fed, and it progressively
sources the whole connected volume. Fluid-agnostic (works with water, oil, anything) and doesn't
touch the distillation system at all — fully usable with this port's simplified 2-fluid setup.

### ~~Chute~~ — DECLINED, not being built (overlaps with Obsidian Pipe's existing vacuum)
A faster, omnidirectional alternative to a hopper, not just "let items fall." One face is the
designated input; instead of only accepting pushed items, it actively vacuums up loose item-drop
entities near that face (up to 3 at once — a hopper can only grab what overlaps its full
collision box) into a small internal buffer, sped up if it's facing straight up (gravity-assist) or
charged by an adjacent MJ source. Separately, every tick it tries to push its buffered items out to
*any* of its other five faces (both block inventories and item-holding entities like item frames),
in randomized order — so a single chute can act as a fast vertical drop-shaft AND a multi-output
junction/sorter at once, which plain gravity or vanilla hoppers can't do.

### ~~Water Gel block + item~~ — DECLINED, not being built
**Depends on the full multi-fluid distillation system you've explicitly deferred** — its spawner
item is crafted from sand + a bucket of `oil_residue`, a fluid that only exists as a distillation
byproduct, not in this port's simplified 2-fluid Oil/Fuel chain. If built at all, it'd need an
invented alternate recipe. Mechanically: right-click a water block with the spawner (consumes 1,
like a snowball) to convert it to a "gel" block that then spreads across the connected water body
over several stages (randomized BFS search converting nearby water each cycle), eventually solidifying
into a walkable, breakable slime-like block. Breaking it drops a Gel item, craftable back into a
water bucket. Purpose: temporarily bridge/platform over lakes/oceans, or reclaim/dam a body of
water, by turning it solid and optionally reversing it later.

---

## robotics

### ~~Zone Planner~~ — DECLINED
A standalone "paint a region onto a map" utility. **Notably, this specific 1.12.2 source snapshot
has already had the actual Robots feature removed** — no Robot item/entity/station exists anywhere
in this codebase, so the Zone Planner currently has no consumer of the zones it defines; it
survives purely as an isolated data-management block. Holds 16 saved zone layers (one per dye
color, each a chunk-based bitmap of "painted" area). GUI has two 10-second processes: feed in a
paintbrush + a map that's already had an area hand-painted on it (via the Paintbrush item
elsewhere) to save that color's zone into the table; or feed in a paintbrush + blank map to get a
fresh map pre-painted with a previously saved zone back out. Given upstream itself has nothing left
to actually *use* a zone for, this is low-value to port unless you have your own plan for what
would consume zone data.

---

## silicon

### ~~Laser~~ — DECLINED, not being built (user: "that looks stupid")
An MJ-powered "wireless" power beam for laser-crafting tables (below) — no physical/pipe
connection needed, just line-of-sight and range (6 blocks). Holds an internal MJ battery fed by an
adjacent engine/pipe; each tick it scans a cone in front of it for crafting-table targets that
currently need power, and roughly once a second randomly picks ONE target among all valid
candidates in range to actually charge (which is why multiple tables sharing a laser visibly "take
turns" / flicker between targets rather than all charging simultaneously). Drains its battery to
push a capped amount of MJ per tick to the chosen target, ramping up output gradually rather than
maxing out instantly on a freshly-placed laser.

Three genuinely different machines sharing one block class in source (parameterized by table type)
and the same laser-power-receiving base class - going through them one at a time below.

### ~~Assembly Table~~ — DECLINED (user: wasn't in original Tekkit pack, multiple Auto Workbenches cover it)
A multi-recipe queue autocrafter. Scans a global recipe registry against its 12-slot buffer for
everything currently craftable, lets you queue multiple different recipes at once via the GUI, and
round-robins the laser's power between queued jobs as materials allow - effectively an assembly
line you configure once and keep feeding. Real overlap with the already-built Auto Workbench (which
only handles one recipe at a time) - the user's own workaround (place several Auto Workbenches) is
a fair substitute given the declined Laser dependency too.

### ~~Advanced Crafting Table~~ — DECLINED (real overlap with the already-built Auto Workbench)
A laser-powered vanilla-recipe autocrafter - a phantom 3×3 blueprint grid (template only) plus a
real materials inventory, continuously trying to match the pattern and craft once it has enough
laser-supplied MJ (500 MJ/craft). Essentially "a player standing at a crafting table," automated -
real, functional overlap with the already-built Auto Workbench (which is genuinely the same concept:
phantom pattern grid + materials + auto-craft, just FE-powered instead of laser-powered).

### ~~Integration Table~~ — DECLINED (real source ships it with ZERO registered recipes - genuinely
non-functional/empty content even in the original, not just a low-priority port target)
An item-*upgrading* machine, not a crafter - takes one "target" item plus up to 8 other ingredient
items and merges them into a modified/upgraded version of the target. Confirmed by direct source
read: `IntegrationRecipeRegistry` is fully wired up but `common/` (the actual compiled source tree)
never populates it with a single recipe - the real recipes (e.g. upgrading a Robot with a redstone
board) only exist in a disabled legacy code path (`src_old_license/`, explicitly commented out of
the build, targeting an incompatible old API). Building this would mean inventing recipes from
scratch, not porting real ones.

### ~~Redstone Chipset item~~ — DECLINED
A tiered crafting/component item (Red/Iron/Gold/Quartz/Diamond), each tier made in the Assembly
Table from redstone dust + a progressively rarer material at rising MJ cost. Feeds into: gate
material recipes (Iron chipset gates Iron gates, Gold chipset gates Gold gates), gate *modifier*
upgrades (Quartz/Diamond chipsets add extra trigger/action parameter slots to an existing gate),
and the Gate Copier's own recipe. BuildCraft's generic "tech tier" currency for the silicon module.

### ~~Gate Copier item~~ — DECLINED
A clone tool for gate configuration. Right-click a configured gate to copy its full trigger/action/
connection setup onto the copier; right-click a different gate with it loaded to paste that
config over. Shift-right-click the copier itself (not aimed at a gate) to clear stored data. Pastes
by slot index regardless of gate tier — copying from a higher-tier gate (more slots) to a
lower-tier one silently drops the extra slots rather than erroring. A big time-saver for wiring up
many identical gates across a network.

### ~~Pluggable Gate — the full redstone-logic system~~ — DECLINED (also drops the Wire system
below, and reduces the Pulsar/Light Sensor/Timer plugs to their standalone value only, since their
real purpose is feeding gate triggers)
**This is the big one — BuildCraft's whole "programmable brain on a pipe" feature**, and genuinely
the most complex single system left unported. A Gate sits on one pipe face and hosts a set of
trigger/action "slots," where the slot count and available parameter richness are set by the
gate's material tier (Clay=1 slot no upgrades, Iron=2, Nether Brick=4, Gold=8) crossed with a
modifier tier (none/Lapis/Quartz/Diamond, which adds extra configurable parameters per trigger/
action but shrinks usable slot-pairs on the higher tiers since each slot needs more room).

Triggers and actions are pulled from a global registry that every module can contribute to —
generic ones like "redstone active," "inventory full/empty," "machine active" come from whatever
tile sits on each of the gate's 6 neighboring faces (queried live, not cached), while pipe-specific
ones (items/fluids currently traversing, wire signal state) come from the pipe itself. Every server
tick, the gate evaluates each slot's trigger, and — critically — adjacent slots can be manually
"linked" together in the GUI into logical groups: within AND-logic gates every trigger in a linked
group must be true; within OR-logic gates any one being true is enough; the group's single combined
result then drives every action in that group simultaneously. This grouping-and-boundary logic is
the real subtlety of the whole system — it's not simple 1-to-1 trigger→action pairing.

Gates can also broadcast colored signals onto the Wire system (see below) via an action, and read
back whether any wire of a given color is currently powered anywhere in the connected network via a
trigger — this is how one gate's condition can flip behavior on a completely different, physically
distant gate. Right-clicking an unconfigured gate opens a checkbox/dropdown GUI to assign triggers/
actions/parameters per slot and toggle the inter-slot links.

Genuinely worth treating as its own project if you want it — it touches the statement-registry
system (which needs to be extensible so every module can plug triggers/actions in), the whole gate
tier/material system, the GUI, and (if you want full fidelity) the Wire system for cross-network
signaling.

### ~~Pluggable Lens~~ — DECLINED
A colored glass disc on a pipe face, two variants. A plain lens *tags* items passing through that
face with its color (on both insertion and exit) — this is how colored item-routing gets its color
tag without needing dyed pipe segments. A filter lens (has iron bars added) instead *restricts*
flow — blocks items whose color doesn't match, and boosts routing priority toward that side for
matching-color items during pathing decisions. Together: plain lenses paint colors on, filter
lenses gate flow by color — BuildCraft's non-Diamond-Pipe way of doing colored sorting at a
junction.

### ~~Pluggable Pulsar~~ — DECLINED
A gate-controllable MJ pulse source. Can be manually toggled by right-click, or driven by two gate
actions: "Pulsar Constant" (stays enabled on a rolling window as long as the action stays active)
or "Pulsar Single" (queues exactly one discrete pulse per activation — used to pull exactly one
item's worth of extraction power at a time). Once enabled, every 20-tick cycle it pushes a pulse of
MJ into the pipe's power behaviour. The standard "clock signal" component for triggering discrete
extraction pulses on a Kinesis power pipe network, typically paired with a gate condition.

### ~~Pluggable Light Sensor~~ — DECLINED (zero standalone value without Gates)
Contributes two triggers ("light low" / "light high") to a gate on the same pipe face, sampling the
light level of the block immediately outside that face against a fixed threshold. No behavior of
its own beyond registering these — a pipe-mounted, gate-only equivalent of a Daylight Sensor.

### ~~Pluggable Timer~~ — DECLINED (zero standalone value without Gates)
Contributes three triggers (short/medium/long — 5/10/15 second periods) that fire true for exactly
one tick each cycle, not a sustained duty cycle. Commonly paired with a Pulsar's "single pulse"
action via a gate to build a periodic extraction clock.

### ~~Pluggable Facade~~ — DECLINED (user: vanilla item frames already cover this)
Disguises a pipe segment's exterior as another block's appearance while keeping most of its real
connectivity/hitbox intact. Crafted at the Assembly Table by feeding in the block to disguise as.
Two types: Basic (one fixed captured appearance) and Phased (up to 17 captured states — one "off"
plus 16 color-indexed states, letting a redstone-driven trigger switch which disguise is currently
shown, e.g. cycling through colored wool/glass looks). Facades can also be "hollow" (a thin shell
that still lets wires/other pipes visually connect through/behind it) versus fully solid. Purely
cosmetic/structural — hides pipe networks behind normal-looking walls, no logic role of its own.

---

## transport

### ~~Kinesis (MJ) power pipes~~ — DECLINED (user is planning a full electricity-system redesign
around a future IndustrialCraft port — physical spinning gears/coils generating real power — so
anything tied to this port's current FE-pipe concept is out of scope until that redesign happens)
Mechanically distinct from item/fluid pipes — there's no discrete object with a render position
traveling through the pipe. Each segment models 6 directional "sections" that run a two-phase
per-tick algorithm: first, real MJ-consuming machines report how much power they want, and that
demand propagates backward face-by-face through the whole connected network so every segment knows
downstream demand *before* anything is pushed; then, whatever power a segment just received gets
redistributed proportionally across every face with outstanding demand. Only Wood-tier power pipes
actually act as receivers/emitters connecting to an adjacent engine — other tiers just relay. The
"flow" you'd see rendered is a cosmetic texture-scroll cue based on flow direction, not an actual
moving-particle animation the way items are.

### ~~RF power pipes~~ — DECLINED (same electricity-redesign reason as Kinesis pipes above)
Line-for-line the identical two-phase request/supply algorithm as the MJ pipes above, just
implemented against Forge's standard RF energy capability instead of BuildCraft's own MJ interface.
In source, RF and MJ Kinesis pipes are literally the same block/item with a different internal flow
implementation selected — not two separately-modeled pipe families. Optional auto-bridging exists
so an MJ pipe can talk to an RF-only receiver and vice versa.

### ~~Wire item / Wire system~~ — DECLINED (only real consumer, Gates, already declined)
The wire item itself is trivial (16 color variants, no logic). The real system is what lets a
gate's action on one part of your pipe network flip a trigger on a *completely different, physically
distant* gate — wires placed on pipe faces form same-colored connected networks (flood-filled
whenever wiring or a gate's broadcast state changes), and any gate anywhere in that connected
network can read "is this color currently powered by anything in my network" regardless of
distance, as long as the pipe path between them is unbroken. This is BuildCraft's long-distance
redstone bus, separate from and independent of the pipes' actual item/fluid/power transport
capability — only physical pipe-segment adjacency matters for wire propagation, not whether that
segment is actually configured to move item/fluid/power. Meaningful mainly once Gates exist (no
point wiring without something to trigger).

### ~~Filtered Buffer block~~ — DECLINED
A compact 9-slot storage buffer where each slot is locked to only accept items matching a
phantom "filter template" placed in a matching filter slot — effectively chest space with a
built-in 9-way type-sorter baked into one block, instead of needing 9 separate filtered chests.
Standalone, no dependency on Gates/Wires/anything else.

### ~~Pluggable Blocker~~ — BUILT DIFFERENTLY 2026-08-03, per user's own design
Real source: the simplest plug — purely seals one face of a pipe (stops items/fluid/power exiting
that side, gives it a capped look), zero logic or capabilities of its own.

**This port's version, refined twice after real QC testing**: user pointed out an unconnected face
is already effectively sealed (no neighbor = nothing happens), so the real use case is a face that
*does* have a real neighbor you specifically want to exclude without physically disconnecting
anything. Built as a wrench-cycled per-face "blocked" state, available on every pipe tier
automatically (item AND fluid pipes) - no content overhead (no item, no recipe, no model) versus
the real source design.

Two real refinements after the first version shipped: (1) targeting by "which face was clicked"
turned out unreliable (same known raycasting-a-thin-arm limitation already documented for Iron/
Wood's own wrench behavior) - redesigned so a plain wrench click anywhere on the pipe instead
cycles a persistent cursor through every real neighbor, disconnecting each one in turn and then
reconnecting them in the same order once all are blocked, no aiming needed at all. (2) the cycle
was narrowed to CONTAINER neighbors only, on user request - pipe-to-pipe blocking was dropped
entirely, since forcibly disconnecting two otherwise-compatible pipes defeats the actual point of
material-specific connectivity rules (e.g. Stone only connecting to Stone). Sneak-click keeps its
existing, unrelated meaning (Iron/Wood's own direction cycling), untouched throughout.

### ~~Pluggable Power Adaptor~~ — DECLINED (same electricity-redesign reason as Kinesis pipes above)
Exposes a pipe's own MJ power capability outward as a synthetic RF energy port, letting adjacent
RF-only machines charge directly off an MJ Kinesis network through a plain pipe segment without
needing a separate physical RF pipe run. One-way (receive only), with careful remainder-banking so
the MJ↔RF integer conversion doesn't lose power to rounding across ticks.

---

## Status: fully triaged (2026-08-03)

Every single item in this document has now been explicitly decided one at a time - built (Gears,
Auto Workbench, Blocker-as-wrench-feature), declined, folded into something else already built
(Flood Gate → planned Pump mode), or ruled a non-gap (`pipe_holder`). Nothing left unreviewed.
There's no more "next natural target" list to work through here - any further work on this mod
would be a genuinely new decision (revisiting something declined, or a feature not in original
BuildCraft at all), not picking the next item off this checklist.
