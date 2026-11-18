# Stealth Takedown (Forge 1.20.1)

Companion mod for the zombieai mod. Sneak up on a zombie that has not noticed you:

1. Sneak up BEHIND an unaware zombie (it must face away), holding any melee weapon, within ~2 blocks -> a right-click icon appears next to it.
2. Hold right click. A white square frame fills around the icon (clockwise from the top-left) while the weapon's wind-up
   plays: the first few ticks are almost frozen, then it continues at normal speed (Better Combat).
   While you hold it, the zombie is pulled in front of you, turned the same way you face, and you move slowly;
   the view narrows slightly. Knives and spears use a stab, heavy weapons an overhead slam.
3. When the frame is full the blow lands and the zombie dies in one hit, whatever its health (small camera kick,
   dull impact sound, a little blood spray).
   Let go early (or lose the weapon / walk away) and the charge is cancelled.

"Unaware" = no target, not hunting, never hurt by anyone. (zombieai zombies that walk to the sound of a sneaking
player still count as unaware unless `investigatingCountsAsAware` is true.)

## Build on GitHub
1. Create a new empty GitHub repo and upload the contents of this folder (the files inside `stealthtakedown/`, so
   `build.gradle` and `.github/` sit at the repo root).
2. Open the **Actions** tab -> **Build mod** (it runs on every push, or press **Run workflow**).
3. When it finishes, download the **stealthtakedown-jar** artifact and unzip it: `stealthtakedown-1.0.0.jar`.

Local build: `gradle build` with JDK 17 and Gradle 8.1.1. Put the jar in `mods/` on server AND client.
Better Combat and zombieai are optional and used through reflection - nothing else to download to compile.

## Config: `config/stealthtakedown-common.toml`
range, backstabOnly, maxFacingDot, grabZombie, slowPlayerWhileCharging, cameraEffects, chargeTicks (24), slowStartTicks (6), slowStartSpeed (0.1), minWeaponDamage, requireSneakWhileCharging,
investigatingCountsAsAware (false), calmZombieDuringCharge, damageWeapon, followThroughTicks, extraTargets, weaponBlacklist.
An existing config file keeps its old values: delete `config/stealthtakedown-common.toml` after updating.

## Notes
* Charge animation: plays the heaviest attack animation of the held weapon, then rewrites Better Combat's speed
  "gears" so the first `slowStartTicks` run at `slowStartSpeed` x normal and the rest is normal speed.
  Tune `chargeTicks`, `slowStartTicks`, `slowStartSpeed` if it looks off for some weapons.
* The slowed animation is local; other players see a normal vanilla swing when the blow lands.
