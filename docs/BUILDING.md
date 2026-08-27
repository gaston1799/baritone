# Building Baritone (1.21.5 fork)

Short guide for building this fork on a fresh machine (e.g. the 65 GB rig).

## Prerequisites
- **JDK 21** (Temurin or Microsoft build). Check: `java -version`
- **Git** (optional, for cloning)
- RAM: gradle.properties sets `org.gradle.jvmargs=-Xmx4G`, so ~6+ GB free is enough. A 65 GB rig is fine.

## Get the code
```bash
git clone -b 1.21.5 https://github.com/gaston1799/baritone.git
cd baritone
```
(The `1.21.5` branch contains all custom features; tag `v1.21.5-custom.1` points at the latest.)

## Build
```bash
gradlew :fabric:build        # compile + run tests (fabric loader)
gradlew :fabric:createDist   # proguard-minify + assemble dist jars
```

If your `java -version` is not 21, either install JDK 21 or point Gradle at it:
```
set JAVA_HOME=C:\path\to\jdk-21
```

## Output
The ready-to-install jar lands in:
```
dist\baritone-standalone-fabric-1.21.5-custom.1.jar
```
(if you have uncommitted changes the name gets a `-dirty` suffix).

Install by copying it into the instance mods folder, e.g.:
```
C:\Users\Naqua\curseforge\minecraft\Instances\1.21.5\mods\
```
**Close Minecraft before overwriting the jar** (a half-written jar while the game is running corrupts it).

## Other loaders / extras
- Forge: `gradlew :forge:createDist`
- NeoForge: `gradlew :neoforge:createDist`
- Everything (all loaders + tests): `gradlew build createDist`

## Custom features quick reference
| Feature | How to use |
|---|---|
| Temp path | `#tps` → move → `#tpe` (planner paths + renders corridor, jump arc, landing box) |
| Jump arc / landing marker | always on (`#renderJumpArc`) |
| Dynamic parkour | automatic (speed/jump boost = longer jumps) |
| Craft any recipe | `#craft <item>` (vanilla + modded, singleplayer, even undiscovered) |
| Self defence combat | `#selfDefence true`; modes: `#attackType swordSweep / swordJump / axeJump / maceSmash` |
| Mace dive-bomb | `#attackType maceSmash` + equipped elytra + firework rockets; rocket-climbs, stows elytra, smashes, then restores/escapes |
| Creeper handling | smash-dive with escape kit (`#selfDefenceCreeperDive`), otherwise avoided |
| Predictive combat movement | enabled by `#selfDefenceCombatMovement`; simulates approach, retreat, hold and diagonal/side strafes against predicted target motion, rejecting walls, drops and hazards |
| Combat movement render | `#selfDefenceRenderCombatMovement`; green=selected, yellow=alternative, red=unsafe, cyan=predicted target |
| Shield/totem/strafe | `#selfDefenceUseShield`, `#selfDefenceTotemHealth`, `#selfDefenceStrafe` |
| Combat failure recorder | `#lastfail`, `#lastfail frames`, or `#lastfail render`; structured traces append to `baritone\combat-failures.jsonl` |
| Logs | `baritone\corrections.log`, `baritone\selfdefence-miss.log` (legacy miss summary), `baritone\combat-failures.jsonl` (bounded tick traces) |
