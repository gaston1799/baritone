(assuming you already have Baritone [set up](SETUP.md))

# Prefix

Baritone's chat control prefix is `#` by default. In Impact, you can also use `.b` as a prefix. (for example, `.b click` instead of `#click`)

Baritone commands can also by default be typed in the chatbox. However if you make a typo, like typing "gola 10000 10000" instead of "goal" it goes into public chat, which is bad, so using `#` is suggested.

To disable direct chat control (with no prefix), turn off the `chatControl` setting. To disable chat control with the `#` prefix, turn off the `prefixControl` setting. In Impact, `.b` cannot be disabled. Be careful that you don't leave yourself with all control methods disabled (if you do, reset your settings by deleting the file `minecraft/baritone/settings.txt` and relaunching).

# For Baritone 1.2.10+, 1.3.5+, 1.4.2+

Lots of the commands have changed, BUT `#help` is improved vastly (its clickable! commands have tab completion! oh my!).

Try `#help` I promise it won't just send you back here =)

"wtf where is cleararea" -> look at `#help sel`

"wtf where is goto death, goto waypoint" -> look at `#help wp` 

just look at `#help` lmao

Watch this [showcase video](https://youtu.be/CZkLXWo4Fg4)!

# Commands

[Tutorial playlist](https://www.youtube.com/playlist?list=PLnwnJ1qsS7CoQl9Si-RTluuzCo_4Oulpa)

**All** of these commands may need a prefix before them, as above ^.

`help`

To toggle a boolean setting, just say its name in chat (for example saying `allowBreak` toggles whether Baritone will consider breaking blocks). For a numeric setting, say its name then the new value (like `primaryTimeoutMS 250`). It's case insensitive. To reset a setting to its default value, say `acceptableThrowawayItems reset`. To reset all settings, say `reset`. To see all settings that have been modified from their default values, say `modified`.

Commands in Baritone:
- `thisway 1000` then `path` to go in the direction you're facing for a thousand blocks
- `goal x y z` or `goal x z` or `goal y`, then `path` to set a goal to a certain coordinate then path to it
- `goto x y z` or `goto x z` or `goto y` to go to a certain coordinate (in a single step, starts going immediately)
- `goal` to set the goal to your player's feet
- `goal clear` to clear the goal
- `cancel` or `stop` to stop everything, `forcecancel` is also an option
- `goto portal` or `goto ender_chest` or `goto block_type` to go to a block. (in Impact, `.goto` is an alias for `.b goto` for the most part)
- `mine diamond_ore iron_ore` to mine diamond ore or iron ore (turn on the setting `legitMine` to only mine ores that it can actually see. It will explore randomly around y=11 until it finds them.) An amount of blocks can also be specified, for example, `mine 64 diamond_ore`. Grouped presets are also supported, for example `mine logs` or the direct shorthand `logs` to mine all log and stem types without picking a specific wood first.
- `craft diamond_sword` to print the dependency tree, show the next actionable step, and then automate the recursive gather/craft chain. `craft diamond_sword 2` does the same for a target count. Craft automation uses ore-aware mining delegation: ores still respect `legitMine`, while non-ore craft materials such as logs use normal cached/scanned mining even when `legitMine` is enabled.
- `click` to click your destination on the screen. Right click path to on top of the block, left click to path into it (either at foot level or eye level), and left click and drag to select an area (`#help sel` to see what you can do with that selection).
- `follow player playerName` to follow a player. `follow players` to follow any players in range (combine with Kill Aura for a fun time). `follow entities` to follow any entities. `follow entity pig` to follow entities of a specific type.
- `wp` for waypoints. A "tag" is like "home" (created automatically on right clicking a bed) or "death" (created automatically on death) or "user" (has to be created manually). So you might want `#wp save user coolbiome`, then to set the goal `#wp goal coolbiome` then `#path` to path to it. For death, `#wp goal death` will list waypoints under the "death" tag (remember stuff is clickable!)
- `build` to build a schematic. `build blah.schematic` will load `schematics/blah.schematic` and build it with the origin being your player feet. `build blah.schematic x y z` to set the origin. Any of those can be relative to your player (`~ 69 ~-420` would build at x=player x, y=69, z=player z-420).
- `schematica` to build the schematic that is currently open in schematica
- `tunnel` to dig and make a tunnel, 1x2. It will only deviate from the straight line if necessary such as to avoid lava. For a dumber tunnel that is really just cleararea, you can `tunnel 3 2 100`, to clear an area 3 high, 2 wide, and 100 deep. If your actual player hitbox is 1 block tall and you set `playerHeight 1`, `tunnel 1 1 <depth>` is also supported.
- `farm` to automatically harvest, replant, or bone meal crops. Use `farm <range>` or `farm <range> <waypoint>` to limit the max distance from the starting point or a waypoint. 
- `axis` to go to an axis or diagonal axis at y=120 (`axisHeight` is a configurable setting, defaults to 120).
- `explore x z` to explore the world from the origin of x,z. Leave out x and z to default to player feet. This will continually path towards the closest chunk to the origin that it's never seen before. `explorefilter filter.json` with optional invert can be used to load in a list of chunks to load.
- `invert` to invert the current goal and path. This gets as far away from it as possible, instead of as close as possible. For example, do `goal` then `invert` to run as far as possible from where you're standing at the start.
- `come` tells Baritone to head towards your camera, useful when freecam doesn't move your player position.
- `blacklist` will stop baritone from going to the closest block so it won't attempt to get to it.
- `eta` to get information about the estimated time until the next segment and the goal, be aware that the ETA to your goal is really unprecise.
- `proc` to view miscellaneous information about the process currently controlling Baritone.
- `repack` to re-cache the chunks around you.
- `gc` to call `System.gc()` which may free up some memory.
- `render` to fix glitched chunk rendering without having to reload all of them.
- `reloadall` to reload Baritone's world cache or `saveall` to save Baritone's world cache.
- `find` to search through Baritone's cache and attempt to find the location of the block.
- `surface` or `top` to tell Baritone to head towards the closest surface-like area, this can be the surface or highest available air space.
- `fill x1 y1 z1 x2 y2 z2 <block>` to fill a rectangular region with a block. Supports relative coordinates (`~`). Use `fill ~ ~ ~ ~10 ~5 ~10 stone` to fill a region from your feet. Use `fill ... air` to clear / break all blocks in a region without a dedicated break command.
- `outline x1 y1 z1 x2 y2 z2 <block>` to build only the outer shell (walls, floor, ceiling) of a bounding box, leaving the interior completely untouched. Same coordinate format as `fill`. Useful for rooms and hollow structures.
- `branchmine` to dig a branch mine pattern from your current position in the direction you are facing. Side branches are cut perpendicular at regular intervals. Optionally pass `branchmine <mainLength> <sideLength> <spacing>` to override the defaults. Settings: `branchMineMainLength`, `branchMineSideLength`, `branchMineSpacing`, `branchMineTargetY`.
- `stripmine` to dig parallel corridors in the direction you are facing. Corridors are spaced `stripMineSpacing` blocks apart (default 3, meaning 2 blocks of uncut wall between each) which exposes every ore between them. Pass `stripmine <length> <corridors>` to override defaults. When the inventory fills and a deposit is configured, the bot paths to the deposit location and shift-clicks items into storage containers whose contents already include that item type — a chest pre-seeded with iron ore gets iron, a diamond chest gets diamonds, etc. Use `stripmine setdeposit` to save your current position as the deposit. Use `stripmine setjunk` to designate a junk chest: after visiting all ore chests, any block-item (cobblestone, andesite, tuff, etc.) not already matched by an ore chest is dumped there. Settings: `stripMineLength`, `stripMineCorridors`, `stripMineSpacing`, `stripMineTargetY`, `stripmineInventoryFreeSlots`.
- `net host [port]` to open a TCP server (default port 11111) so this instance becomes master. `net connect <ip> [port]` to join as a worker. `net send <command>` to broadcast any Baritone command to all connected workers simultaneously. `net list` / `net stop` to inspect or shut down the connection. Useful for controlling multiple Minecraft clients over LAN or WAN.
- `version` to get the version of Baritone you're running
- `damn` daniel

All the settings and documentation are <a href="https://github.com/cabaletta/baritone/blob/master/src/api/java/baritone/api/Settings.java">here</a>. If you find HTML easier to read than Javadoc, you can look <a href="https://baritone.leijurv.com/baritone/api/Settings.html#field.detail">here</a>.

There are about a hundred settings, but here are some fun / interesting / important ones that you might want to look at changing in normal usage of Baritone. The documentation for each can be found at the above links.
- `allowBreak`
- `allowSprint`
- `allowSprintJump`
- `selfDefence`
- `attackType` (`swordSweep`, `swordJump`, `axeJump`)
- `selfDefenceMode` (`inPlace`, `shortChase`, `fullChase`)
- `allowPlace`
- `allowParkour`
- `allowParkourPlace`
- `allowIceParkour`
- `parkourTakeoffTiming` (`vanilla`, `dynamic`, `jam`, `headHitter`, `late`)
- `blockPlacementPenalty`
- `renderCachedChunks` (and `cachedChunksOpacity`) <-- very fun but you need a beefy computer
- `avoidance` (avoidance of mobs / mob spawners)
- `legitMine`
- `followRadius`
- `backfill` (fill in tunnels behind you)
- `buildInLayers`
- `buildRepeatDistance` and `buildRepeatDirection`
- `worldExploringChunkOffset`
- `acceptableThrowawayItems`
- `blocksToAvoidBreaking`
- `mineScanDroppedItems`
- `veinMine` (expand ore targets to the full connected vein automatically)
- `silkTouchBlocks` (blocks that autoTool will prefer a Silk Touch tool for, e.g. glass, ice)
- `allowDiagonalAscend`
- `swimDeadband` (for example `#swimDeadband 0.3`)
- `autoEat` / `autoEatAtHunger` (automatically eat food from inventory when hungry)
- `autoArmor` (automatically equip the best armor from inventory)
- `autoTotem` / `autoTotemHealth` (move a totem of undying to offhand when health is low)
- `dropTrashItems` / `trashItems` (automatically drop junk items like tuff or rotten flesh)
- `autoRespawn` / `autoRespawnTimeoutMs` (automatically click respawn after dying; default timeout is 4000 ms)




# Troubleshooting / common issues

## Why doesn't Baritone respond to any of my chat commands?
This could be one of many things.

First, make sure it's actually installed. An easy way to check is seeing if it created the folder `baritone` in your Minecraft folder.

Second, make sure that you're using the prefix properly, and that chat control is enabled in the way you expect.

For example, Impact disables direct chat control. (i.e. anything typed in chat without a prefix will be ignored and sent publicly). **This is a saved setting**, so if you run Impact once, `chatControl` will be off from then on, **even in other clients**.
So you'll need to use the `#` prefix or edit `baritone/settings.txt` in your Minecraft folder to undo that (specifically, remove the line `chatControl false` then restart your client).


## Why can I do `.goto x z` in Impact but nowhere else? Why can I do `-path to x z` in KAMI but nowhere else?
These are custom commands that they added; those aren't from Baritone.
The equivalent you're looking for is `goto x z`.
