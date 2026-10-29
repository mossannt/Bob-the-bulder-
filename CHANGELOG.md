# Changelog

## v7 - smart player bot
- Path-finding (A*): walks, jumps, drops, avoids lava/fire/cactus/water. Floating buildings: builds TEMPORARY bridges and pillars (cobblestone/dirt/... from the inventory; spawned automatically in creative; fetched from the chests in survival) and REMOVES them all at the end (last placed first).
- Block with no neighbour at all (floating): temporary support block is placed first.
- Breaks wrong blocks, picks up every drop, and stores everything the build does not need in loot chests (z-2, z-4 ... behind the material chests; chests are placed automatically if a chest item is in the inventory / creative). Material chests are the fallback.
- Inventory check now also allows cobblestone/dirt/planks (scaffold) and chests.
- Unreachable blocks are skipped quickly instead of walking into walls.
- Elytra: if the bot has an elytra (worn or in the inventory) and the goal is > 45 blocks away or high up, it flies. The standing point it will land on is shown as a cyan box with a beam. Fire-work rockets are used when available; without them it only glides downhill (needs enough height).

## v8.1 - switch blocks
- Settings -> "2. View Materials": every material row has a small switch button (⇄) on its left.
- It opens a list of ALL blocks of the game with a search field (name or id). Click a block: every block of that material in the schematic becomes the chosen block (facing / half / type are kept when the new block has them). The hologram, the material list and the chest count update at once.
- "Reset blocks" (bottom of the materials screen) restores the original schematic. Switching is blocked while a build is running; chests must be set again (step 3).

## v9 - Digger + scrollbars
- Scrollbars: Settings, Bot control, Build mode, Digger scroll with the mouse wheel and a draggable bar when they do not fit the window (small window / big GUI scale). Materials, schematic list and block picker got a draggable bar as well (the lists are paged: the bar moves between pages).
- Digger (key G, or Settings -> "Digger (dig an area)..."): look at a block -> "Set A", look at another block -> "Set B" (or "block under me"). The box between A and B is shown in yellow (A green, B red). The screen counts the blocks, estimates the drops and tells how many chests they need (27 slots each).
  - "Resources: Collect in chests": the bot picks up the drops and stores them in chests it places west / north of the area (chest items from the inventory, spawned in creative).
  - "Resources: Leave them": it only digs, the drops stay on the ground.
  - Digs layer by layer from the top (snake), uses the best tool in the inventory, builds temporary pillars / bridges when it must get out of the hole and removes them at the end. Blocks next to lava, unbreakable blocks and unreachable blocks are skipped. Fluids are not removed. Stop button / Settings -> Stop cancels.

## v11 - saved progress + dig resource filter
- Progress is saved to `<config>/bobsc-progress.json`: every 10 s, on Stop, when leaving the world and when quitting the game. Settings -> "Saved progress (resume)...": Resume / Delete for the last build and the last dig (works after restarting the game).
  - Build: schematic, position, chests, block switches are restored; press "6. Start Building" - finished blocks are recognised in the world and skipped.
  - Dig: area A/B and collect/leave are restored and the dig continues (finished cells are air). Both are cleared automatically when finished.
- Digger -> "Resource filter...": every drop type can be Keep or Throw (presets: Keep all / Ores only / Throw blocks). Thrown types are not picked up, are not counted for the chests and are thrown away when the inventory gets full (64 scaffold blocks are always kept). The filter is remembered between sessions.

## v12 - buildings in the sky
- Path-finder: the search now knows that every block of height costs a pillar block, so it climbs straight up instead of exploring the whole ground around the bot first (that wasted the full 5000-step search on every plan: lag spikes and "unreachable" skips for high buildings).
- Block look-ups are cached during a search and no longer allocate objects (much less stutter).
- Pillar: places exactly one block per jump (no repeated placing while landing), no extra wait ticks.

## v13 - pause / continue + restore after rejoining
- Pause: Settings -> "Pause bot (J)" (Digger: "Pause") or key J. The bot stops and keeps its progress; the same button / key J becomes "Continue bot" and carries on where it stopped (finished blocks / cells are skipped).
- Leaving a world or server (or quitting the game) while the bot works pauses it and saves everything. Coming back to the SAME world / server:
  - same game session: the building (hologram), chests and block switches are still there;
  - after restarting the game: the saved building / dig area is restored automatically 2 s after joining (chat message), then press J.
- A saved job is only restored / continued in the world, server and dimension it was saved in.

## v14 - tidy menus
- Key N is now a short main menu: bot status line, Pause / Continue / Stop, and one button per area: Build a schematic, Digger, Bot character, Saved progress, Options.
- Build a schematic: Choose schematic + the six steps in order (green tick = done, chest count shown).
- Options: hologram look, creative fill. Every sub-menu has a Back button to the main menu.

## v15 - safety and robustness
- Safety (Options -> "Safety", ON by default; not in creative): pauses when the bot dies, is low on health, or is hungry without food; eats by itself (needs food in the inventory); fights monsters that come within 6 blocks (sword / axe if it has one). Inventory check now allows a sword and food.
- Temporary pillars / bridges are saved with the progress, so they are removed after you come back and continue.
- Tools that are almost broken are not used while another tool is available.
- Closed wooden doors and fence gates (not part of the building, not iron) are opened and walked through.
- A building that is too far away (chunks not loaded): the bot walks towards it and waits instead of skipping blocks; pauses after about a minute.
- Options -> "Slow mode": longer pauses between placements (not an anti-cheat bypass).
- A warning when a schematic has more than 200 000 blocks (hologram lag).

## v17 - safer bot
- Fights never step onto unsafe cells (ledge, lava, water, cactus ...) and never chase from a temporary pillar / bridge. If there is no safe step the bot does not freeze: it strafes, attacks, or stops waiting after 3 s and carries on working.
- Low health with a monster near: totem of undying / shield go to the off-hand (shield blocks while the sword recharges), golden apple is eaten, the bot keeps fighting. Eating is interrupted when a monster comes close and only started when it is far (> 8 blocks, not a shooter).
- Creepers: hit once, back off 25 ticks (backwards or sideways), repeat; a lit fuse also makes it back off; at low health it only flees.
- The fight timer only counts ticks without a hit: after 30 s without progress the monster is ignored for 10 s (never while adjacent); at low health the bot pauses instead.
- Skeletons / blazes are noticed from 16 blocks, other monsters from 6; fights also work in water; an open chest is closed when a monster hits the bot.
- Burning: walks to water within 6 blocks. Running out of air: swims up. Poison / wither: milk bucket.
- Inventory check allows totem, shield and milk bucket.
- Digger: blocks next to water are skipped like lava; under sand / gravel the bot steps out of the column before mining.
- Elytra: not started below 5 hearts; brakes within 10 blocks of the ground.

## v18 - clean, simple menus
- Every menu has the same look: title + one status line on top, big buttons with a short grey explanation under them, gold headings, and a Back / Close button that is always visible at the bottom. The middle scrolls (mouse wheel or the bar on the right).
- Main menu (N): the bot status, Pause / Stop (or Continue), and one button per job: Build a building, Dig an area, Helper bot, Saved jobs, Settings. The shortcut keys are listed at the bottom.
- Build a building: seven steps in order. Green tick = done, yellow arrow = next step, "Next step" line on top: 1 Choose a building, 2 Place the building, 3 See the materials, 4 Put the chests, 5 Check the chests, 6 Check my inventory, 7 START building.
- Who builds?: My character / Helper bot / I build by hand, each with one sentence of help.
- Dig an area: 1. Choose the area (corner A / corner B), 2. What happens to the items, 3. Go. Info and chest count at the bottom.
- Settings: clear ON / OFF colours and one sentence for each option.
- Place the building: small panel behind the buttons, short instructions, Cancel button.
- Choose a building and Materials scroll like all other menus (no more pages).
