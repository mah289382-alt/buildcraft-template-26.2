# BuildCraft NeoForge 26.2 Port — Content Checklist

Generated 2026-08-02 by comparing the original BuildCraft 1.12.2 source's real registered
blocks/items (`reference/buildcraft-source/common/buildcraft/*/BC*Blocks.java` /
`BC*Items.java`, one class pair per module) against this port's own `*Content.java` /
`*Fluids.java` registration classes.

Lives outside `src/main/java` on purpose — pure documentation, not compiled.

Legend: ✅ done · 🟡 partial/deviated · ❌ not started · ⬛ intentionally out of scope (per prior
user decisions — see project memory) · ⏳ waitlisted, undecided

---

## builders (7 blocks + 3 items in original)

| Original | Status | Notes |
|---|---|---|
| `quarry` | ✅ | Full server logic + rendering, see project memory |
| `frame` | ✅ | Quarry frame block |
| `marker` (volume + path, actually registered under `core` originally) | ✅ | Ported here instead of `core`, functions as both volume/path marker |
| `filler` | ⬛ | Declined by user — not being built |
| `builder` | ⬛ | Declined by user — not being built |
| `architect` (Architect Table) | ⬛ | Declined by user — not being built |
| `library` (Electronic Library) | ⬛ | Declined by user — not being built |
| `replacer` | ⬛ | Declined by user — not being built |
| `item.snapshot` (blueprint/template) | ⬛ | Declined by user — not being built |
| `item.schematic.single` | ⬛ | Declined by user — not being built |
| `item.filler_planner` | ⬛ | Declined by user — not being built |

**builders summary: 3/10 real content entries done; the remaining 7 (the whole snapshot/blueprint
sub-chain) declined by user — builders module considered closed out at Quarry + Frame + Marker.**

---

## core (6 blocks + 9 items in original, some dev-only)

| Original | Status | Notes |
|---|---|---|
| `item.wrench` | ✅ | `WrenchItem`, handles pipe/engine interaction |
| `block.marker.volume` / `.path` | ✅ | Ported under `builders` package instead (see above) |
| `block.spring` (oil spring worldgen source) | 🟡 | No spring *block*; oil instead generates via vanilla `lake` Feature datapack (deliberate simplification, see fuel-status memory) |
| `block.decorated` (multi-state utility block) | ⬛ | Declined by user — not being built |
| `block.power_tester` (dev-only) | ⬛ | Dev-only in original, skip |
| `block.engine.bc` base tiers (Wood/Creative) | ✅ | Ported under `energy` module as separate blocks instead (see below) |
| `item.gear.wood/stone/iron/gold/diamond` (5 tiers) | ✅ | Rebuilt 2026-08-02: real items/textures/recipes, and rewired into the 8 real recipes that use them (Quarry, Mining Well, Pump, Wrench, 3 Engines, Stripes pipe) |
| `item.paintbrush` | ⬛ | Declined by user — not being built |
| `item.list` | ⬛ | Declined by user — only real consumer is the Gate system, which isn't built |
| `item.map_location` | ⬛ | Declined by user — not being built |
| `item.marker_connector` | ⬛ | Declined by user — both jobs serve already-declined features |
| `item.volume_box` | ⬛ | Declined by user — not being built |
| `item.fragile_fluid_shard` | ⬛ | Declined by user — not being built |
| `item.goggles` (dev-only) | ⬛ | Dev-only in original, skip |

**core summary: 4/12 real (non-dev) content entries done (wrench, marker, gears); the remaining 6 all
explicitly declined by user, core module considered closed out.**

---

## energy (mj_dynamo + engine tiers + ~10 fluids + 1 item in original)

| Original | Status | Notes |
|---|---|---|
| Engine tiers (real modern set: Redstone/Stirling/Combustion + Creative) | ✅ | All 4 tiers built as separate blocks (`engine_redstone`, `engine_stirling`, `engine_combustion`, `engine_creative`) — this port's per-material-block pattern instead of source's single metadata-variant block, functionally equivalent |
| `block.mj_dynamo` (RF→MJ dynamo) | ⬛ | Waitlisted → now effectively declined 2026-08-03: user is planning a full electricity-system redesign around a future IndustrialCraft port (physical spinning gears/coils), so any FE-bridge machine is out of scope until that redesign happens |
| RF Engine tier | ⬛ | Same reasoning as MJ Dynamo above — out of scope until the electricity redesign |
| `item.glob.oil` | ❌ | Not started — this port instead gave Oil a real fluid+bucket (see factory fluids) rather than a solid "glob" item; genuinely unused/dead code in real source too (confirmed via grep, no recipe/consumer anywhere), not worth building either way |
| ~10 base fluids w/ heat variants (full thermal distillation) | ⬛ | Explicit user scope-down to the classic 2-fluid Oil→Fuel chain instead (see fuel-status memory) |

**energy summary: engine tiers done (the core deliverable); dynamo/glob-item/full-fluid-system explicitly out of scope or not started.**

---

## factory (11 blocks + 3 items in original)

| Original | Status | Notes |
|---|---|---|
| `block.mining_well` | ✅ | Real shared `TileMiner` mechanic |
| `block.pump` | ✅ | Real shared `TileMiner` mechanic, flood-fill drain (real 64-block radius) |
| `block.tube` (pump/well internal shaft) | ✅ | `mining_well_tube` + `pump_tube`, block-only per-machine (see TubeBlock javadoc for why 2 vs. source's 1 shared block) |
| `block.tank` | ✅ | Real vertical-stacking generic fluid storage, baked model |
| `block.distiller` (Refinery-equivalent, Oil→Fuel) | ✅ | Built as `refinery` — real recipe/energy pacing, GUI, capabilities |
| `block.autoworkbench.item` | ✅ | Built + user-confirmed working in-game (real recipe, real tag-aware matching, real FE charge/decay) |
| `block.flood_gate` | 🟡 | Declined as standalone block — will fold its fill behavior into the Pump as a wrench-toggleable mode instead (not yet implemented) |
| `block.chute` | ⬛ | Declined by user — overlaps with Obsidian Pipe's existing item vacuum |
| `block.heat_exchange` | ⬛ | Only needed for full thermal distillation, out of scope alongside that system |
| `block.water_gel` + `item.water_gel_spawn` | ⬛ | Declined by user — not being built |
| `item.gel` | ⬛ | Declined by user (tied to Water Gel) — not being built |
| `item.plastic.sheet` (dev-only) | ⬛ | Dev-only in original, skip |

**factory summary: 6/9 real (non-dev, non-heat-exchange) content entries done (added Auto Workbench
2026-08-02) — this is the most complete secondary module. Flood Gate/Chute/Water Gel all explicitly
declined or redirected by user, not gaps.**

---

## lib (infrastructure only — 0 blocks, 3 conditional debug/guide items in original)

Pure shared infrastructure in the original (base classes, networking, GUI framework) — no
gameplay content of its own to port. This project doesn't have a separate `lib` package;
equivalent shared code lives inline in each module. Guide book (`item.guide`/`.guide.note`) and
debugger tool (`item.debugger`) not built — low priority, non-blocking utility items.

---

## robotics (1 block, 0 items in original — already minimal upstream)

| Original | Status | Notes |
|---|---|---|
| `block.zone_planner` | ⬛ | Declined by user — no real consumer left upstream (Robots already removed from this source version) |

**robotics summary: 0/1, fully triaged and declined 2026-08-02 — module considered closed out, not
an open gap.**

---

## silicon (6 blocks + 8 items in original)

| Original | Status | Notes |
|---|---|---|
| `block.laser` | ⬛ | Declined by user ("that looks stupid") |
| `block.assembly_table` | ⬛ | Declined by user — real overlap with the built Auto Workbench, and Tekkit didn't have it either |
| `block.advanced_crafting_table` | ⬛ | Declined by user — real overlap with the built Auto Workbench |
| `block.integration_table` | ⬛ | Declined by user — also confirmed via direct source read that real source ships this with ZERO registered recipes (genuinely non-functional even upstream) |
| `block.charging_table` / `.programming_table` (dev-only) | ⬛ | Dev-only in original, skip |
| `item.redstone_chipset` | ⬛ | Declined by user |
| `item.gate_copier` | ⬛ | Declined by user |
| `item.plug.gate` (logic gate pluggable) | ⬛ | Declined by user — this is the real "wire/redstone-gate logic system" |
| `item.plug.lens` | ⬛ | Declined by user |
| `item.plug.pulsar` | ⬛ | Declined by user |
| `item.plug.light_sensor` | ⬛ | Declined by user (zero standalone value without Gates) |
| `item.plug.timer` | ⬛ | Declined by user (zero standalone value without Gates) |
| `item.plug.facade` | ⬛ | Declined by user — vanilla item frames already cover the disguise use case |

**silicon summary: 0/12, but fully triaged — every item explicitly declined by user 2026-08-02, module
considered closed out, not an open gap.** This is also where all pipe gate/plug/facade content
actually lives upstream (not `transport`), worth remembering if this decision is ever revisited.

---

## transport (2 blocks + 4 items + 46 pipe tiers in original)

| Original | Status | Notes |
|---|---|---|
| Item (solid) pipes — 16 real tiers + Structure = 17 | ✅ **17/17** | Cobblestone, Stone, Iron, Gold, Void, Quartz, Sandstone, Clay, Wood, Diamond, Wood-Diamond, Lapis, Daizuli, Obsidian, Stripes, Emzuli, Structure — all built, real textures/models/recipes, colour-tagging (Lapis/Daizuli), filter GUIs (Diamond/Wood-Diamond/Emzuli) |
| Fluid ("waterproof") pipes — 11 real tiers | ✅ **11/11** | Cobblestone, Iron, Void, Gold, Stone, Quartz, Sandstone, Clay, Wood, Wood-Diamond, Diamond — all built; flow-engine rewrite + long animation saga resolved (see fluid-pipes-status memory) |
| `item.waterproof` (pipe upgrade item) | ✅ | Built as `pipe_sealant`, real Cactus→Green Dye→Sealant recipe chain (deliberately NOT the source's Slime Ball shortcut, per user decision) |
| Power (Kinesis/MJ) pipes — 9 tiers | ⬛ | Declined by user — planning a full electricity-system redesign around a future IndustrialCraft port (physical spinning gears/coils), anything tied to this port's current FE-pipe concept is out of scope until then |
| RF power pipes — 9 tiers | ⬛ | Declined by user — same electricity-redesign reason |
| `item.wire` (gate wiring) | ⬛ | Declined by user — tied to the already-declined silicon gate system |
| `block.filtered_buffer` | ⬛ | Declined by user |
| `block.pipe_holder` | ⬛ | Architectural difference only — this port's pipes are direct placeable blocks, no separate holder-block layer; not a real content gap |
| `item.plug.blocker` | ✅ | Built differently by user design 2026-08-03: not a standalone item, but a wrench-toggleable per-face "blocked" state on every pipe tier (both item and fluid pipes) — see `PipeBlockEntity`/`FluidPipeBlockEntity.toggleFaceBlocked` |
| `item.plug.power_adaptor` | ⬛ | Declined by user — same electricity-redesign reason |

**transport summary: all 28 real item+fluid pipe tiers done (the bulk of the module's actual
content) plus the Sealant chain. Power/RF pipes + Wire + Power Adaptor all declined 2026-08-02
(electricity-system redesign planned around a future IndustrialCraft port). Filtered Buffer and
Blocker are the only 2 items in the whole checklist still awaiting a decision.**

---

## Overall picture

| Module | Real content done | Total real content | Rough % | Remaining gap status |
|---|---:|---:|---:|---|
| builders | 3 | 10 | 30% | Rest declined by user, module closed out |
| core | 4 | 12 | 33% | Rest declined by user, module closed out |
| energy | 1 (engine tiers, the main deliverable) | 5 | ~50% functionally, 20% by count | Rest declined by user (electricity redesign planned), module closed out |
| factory | 6 | 9 | 67% | Rest declined/redirected by user, module closed out |
| lib | n/a (infrastructure) | — | — | — |
| robotics | 0 | 1 | 0% | Declined by user, module closed out |
| silicon | 0 | 12 | 0% | Declined by user, module closed out |
| transport | 30 (28 pipes + sealant + Blocker-as-wrench-feature) | 38 | 79% | Power/RF pipes + Wire + Power Adaptor declined (electricity redesign); Filtered Buffer declined; only `pipe_holder` architectural non-gap remains |

**Every module in the entire checklist is now fully triaged** (2026-08-03) — nothing left as an
unreviewed gap. Biggest completed area: transport (pipes) — essentially the whole module's tier
content, plus the wrench-toggle Blocker feature. Everything not built was either explicitly
declined by the user, folded into a different already-built feature (Flood Gate → planned Pump
mode, Blocker → wrench toggle), or is a real architectural non-gap (`pipe_holder`).

Matches this project's own stated priority order (quarry → pipes → engines → fuel) — those are
exactly the 4 areas showing highest completion above.

---

## Non-block items & recipe completeness (checked 2026-08-02)

**Every recipe file that exists (51 total, `src/main/resources/data/buildcraft/recipe/`) targets
a real registered item, and every currently-registered craftable item has exactly one recipe.**
Cross-checked file-by-file against this port's `*Content.java`/`*Fluids.java` registries — nothing
is registered-but-uncraftable, and nothing references a non-existent item (no crash risk from a
dangling recipe ingredient).

Breakdown of the 51: 17 item-pipe base recipes + 11 fluid-pipe upgrade recipes + 11 fluid-pipe
revert recipes + 2 Sealant-chain recipes (dye smelt + Sealant craft) = 41 pipe-related, plus
`quarry`, `mining_well`, `pump`, `tank`, `refinery`, `wrench`, `marker`, `engine_redstone`,
`engine_stirling`, `engine_combustion` = 10 machine recipes. Oil/Fuel buckets correctly have **no**
recipe file (filled by right-clicking the fluid, same as vanilla buckets — matches source, not a
gap). `frame` and `engine_creative` also correctly have no recipe (source doesn't let you craft
either — Frame is placed by the Quarry itself at runtime, Creative Engine is creative-menu-only).

**The real finding is upstream of recipes: this port has almost no standalone (non-block) items at
all.** Only 2 exist — `wrench` and `pipe_sealant` — everything else in the "non-block items"
column of every module table above (gears, paintbrush, list, map_location, marker_connector,
volume_box, fragile_fluid_shard, glob.oil, wire, all 8 silicon plug types, redstone_chipset,
gate_copier, snapshot/schematic, guide book, debugger, etc.) was simply never registered as an
item in the first place — so there's no missing-recipe gap to close for them individually, the
item itself doesn't exist yet.

**Gears specifically confirmed gone, and consistently worked around, not half-done**: none of the
5 gear tiers (`gear_wood/stone/iron/gold/diamond`) are registered (matches the checklist's earlier
note that they were reverted with an old rogue task's changes and never rebuilt). Read every real
source recipe that normally consumes a gear (quarry, mining well, pump, wrench, all 3 real engine
tiers, the Stripes pipe) and compared it line-by-line against this port's equivalent recipe JSON:
in every single case the gear ingredient was swapped for a plain vanilla material one tier down
(e.g. real `gearIron` in the Mining Well recipe → this port just uses another `iron_ingot`; real
`gearStone` in the Wrench → `cobblestone`; real `gearIron`/`gearGold`/`gearDiamond` in the Quarry →
plain `iron_ingot`/`gold_ingot`/`diamond`). This is a real, consistent design simplification, not
an oversight or a half-migrated recipe — but it does mean the mod currently has **no gear
crafting-progression chain at all**: every high-tier machine is directly craftable from raw
ingots/ore instead of needing an intermediate gear item first, which is a genuine gameplay-pacing
change from real BuildCraft, worth a deliberate decision (rebuild gears for real, or keep the
flattened economy) rather than leaving it as an implicit side effect of the earlier revert.

**One more real, smaller deviation found while checking this**: this port's `marker` recipe
(`gold_ingot` + `redstone_torch`) doesn't match EITHER of the source's two separate recipes
(`marker_volume` = light-blue dye + torch, `marker_path` = green dye + torch) — expected, since
this port merged both into one Marker block/item, but the actual ingredient choice (gold ingot)
wasn't derived from either source recipe, just picked fresh. Not wrong, just worth knowing it's an
invented recipe, not a ported one.

**Pipe color variants not built**: real source additionally lets you craft every one of the 17
item-pipe tiers in 16 dye colors directly (colored glass in the crafting grid instead of colorless
— 17 recipes per tier, ~289 total across the mod) via `BCTransportRecipes.addPipeRecipe`'s colour
loop. This port only has the 1 colorless recipe per tier — a real, unflagged scope reduction (separate
from the intentional Lapis/Daizuli colour-tagging *routing* feature, which is unrelated and IS
built). Likely fine to leave out (this port's pipes aren't metadata-color-variant items the way
source's are), but noting it since it's a real difference from "recipe parity," not just a missing
extra block/item.
