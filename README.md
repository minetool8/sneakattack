# Stealth Takedown (Forge 1.20.1)

Companion mod for the zombieai mod. Sneak up on a zombie that has not noticed you:

1. Sneak, hold any melee weapon, get within ~2 blocks of an unaware zombie -> a right-click prompt appears next to it.
2. Hold right click. The weapon's wind-up plays slowly (Better Combat) while a bar fills.
3. When the bar is full the blow lands and the zombie dies in one hit, whatever its health.
   Let go early (or lose the weapon / walk away) and the charge is cancelled.

"Unaware" = no target, not hunting, never hurt by anyone and, if zombieai is installed, not walking to a sound.

## Build on GitHub
1. Create a new empty GitHub repo and upload the contents of this folder (the files inside `stealthtakedown/`, so
   `build.gradle` and `.github/` sit at the repo root).
2. Open the **Actions** tab -> **Build mod** (it runs on every push, or press **Run workflow**).
3. When it finishes, download the **stealthtakedown-jar** artifact and unzip it: `stealthtakedown-1.0.0.jar`.

Local build: `gradle build` with JDK 17 and Gradle 8.1.1. Put the jar in `mods/` on server AND client.
Better Combat and zombieai are optional and used through reflection - nothing else to download to compile.

## Config: `config/stealthtakedown-common.toml`
range, chargeTicks, minWeaponDamage, requireSneakWhileCharging, investigatingCountsAsAware, calmZombieDuringCharge,
damageWeapon, followThroughTicks, extraTargets, weaponBlacklist.

## Notes
* Charge animation: plays the heaviest attack animation of the held weapon with length = chargeTicks / upswing.
  If it looks off for some weapons, tune `chargeTicks` / `followThroughTicks` (BetterCombatBridge.java).
* The slowed animation is local; other players see a normal vanilla swing when the blow lands.
