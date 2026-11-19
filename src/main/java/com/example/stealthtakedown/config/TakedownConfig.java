package com.example.stealthtakedown.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class TakedownConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue RANGE;
    public static final ForgeConfigSpec.IntValue CHARGE_TICKS;
    public static final ForgeConfigSpec.DoubleValue MIN_WEAPON_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_SNEAK_WHILE_CHARGING;
    public static final ForgeConfigSpec.BooleanValue INVESTIGATING_COUNTS_AS_AWARE;
    public static final ForgeConfigSpec.BooleanValue CALM_ZOMBIE_DURING_CHARGE;
    public static final ForgeConfigSpec.BooleanValue DAMAGE_WEAPON;
    public static final ForgeConfigSpec.IntValue FOLLOW_THROUGH_TICKS;
    public static final ForgeConfigSpec.DoubleValue RESIST_CHANCE;
    public static final ForgeConfigSpec.BooleanValue GRAB_VOICE;
    public static final ForgeConfigSpec.BooleanValue BACKSTAB_ONLY;
    public static final ForgeConfigSpec.DoubleValue MAX_FACING_DOT;
    public static final ForgeConfigSpec.BooleanValue GRAB_ZOMBIE;
    public static final ForgeConfigSpec.BooleanValue SLOW_PLAYER;
    public static final ForgeConfigSpec.BooleanValue CAMERA_EFFECTS;
    public static final ForgeConfigSpec.IntValue SLOW_START_TICKS;
    public static final ForgeConfigSpec.DoubleValue SLOW_START_SPEED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_TARGETS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WEAPON_BLACKLIST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("takedown");
        RANGE = b.comment("How close (blocks, measured to the zombie's body) the player must be for the prompt to appear.")
                .defineInRange("range", 2.0, 0.5, 4.0);
        CHARGE_TICKS = b.comment("Charge time in ticks (20 = 1 second): from pressing right click until the blow lands.")
                .defineInRange("chargeTicks", 24, 4, 200);
        RESIST_CHANCE = b.comment("Chance (0..1) that the grabbed zombie breaks free: it turns around, shakes its head violently,",
                        "shoves the player away and attacks. 0.25 = 25%.")
                .defineInRange("resistChance", 0.25, 0.0, 1.0);
        GRAB_VOICE = b.comment("The grabbed zombie groans in pain, muffled (low and quiet).")
                .define("grabVoice", true);
        BACKSTAB_ONLY = b.comment("The takedown only works from behind: the zombie must be facing away from the player.")
                .define("backstabOnly", true);
        MAX_FACING_DOT = b.comment("How far behind the zombie the player must be. The zombie's facing direction dotted with the direction",
                        "to the player must be below this: -1 = exactly behind, 0 = anywhere behind its shoulder line, -0.2 = rear cone (default).")
                .defineInRange("maxFacingDot", -0.2, -1.0, 1.0);
        GRAB_ZOMBIE = b.comment("While charging, the zombie is pulled in front of the player and turned to face the same way (a grab from behind).")
                .define("grabZombie", true);
        SLOW_PLAYER = b.comment("The player moves slowly while holding the zombie.")
                .define("slowPlayerWhileCharging", true);
        CAMERA_EFFECTS = b.comment("Client: subtle camera focus (slight zoom) while charging and a small camera kick when the blow lands.")
                .define("cameraEffects", true);
        MIN_WEAPON_DAMAGE = b.comment("A held item counts as a melee weapon when its main-hand attack damage bonus is at least this.",
                        "Bare hands and guns have none, swords/axes/tools and modded melee weapons do.")
                .defineInRange("minWeaponDamage", 2.0, 0.0, 100.0);
        REQUIRE_SNEAK_WHILE_CHARGING = b.comment("Cancel the charge if the player stops sneaking.")
                .define("requireSneakWhileCharging", false);
        INVESTIGATING_COUNTS_AS_AWARE = b.comment("With the zombieai mod: a zombie that is walking to a sound is NOT a valid victim.",
                        "Leave this false: zombieai zombies hear a sneaking player from a few blocks away and walk to the noise,",
                        "so with true the prompt would almost never appear. They only count as aware once they have a target.")
                .define("investigatingCountsAsAware", false);
        CALM_ZOMBIE_DURING_CHARGE = b.comment("Hold the zombie still and clear its target while the blow charges, so it cannot ruin the takedown.")
                .define("calmZombieDuringCharge", true);
        DAMAGE_WEAPON = b.comment("The weapon loses 1 durability per takedown (not in creative).")
                .define("damageWeapon", true);
        FOLLOW_THROUGH_TICKS = b.comment("Client: ticks after the blow lands before the swing animation is faded out. 0 = let it play fully.")
                .defineInRange("followThroughTicks", 0, 0, 60);
        SLOW_START_TICKS = b.comment("Client, Better Combat animation: for this many ticks the start of the wind-up is almost frozen,",
                        "then it continues at normal speed. 0 = no slow start.")
                .defineInRange("slowStartTicks", 6, 0, 60);
        SLOW_START_SPEED = b.comment("Client: animation speed during the slow start, as a fraction of normal (0.1 = 10%).")
                .defineInRange("slowStartSpeed", 0.1, 0.01, 1.0);
        EXTRA_TARGETS = b.comment("Entity ids that can be taken down besides everything that extends the vanilla Zombie (husk, drowned, zombie villager...).")
                .defineListAllowEmpty("extraTargets", List.of(), o -> o instanceof String);
        WEAPON_BLACKLIST = b.comment("Item ids that never count as a melee weapon.")
                .defineListAllowEmpty("weaponBlacklist", List.of(), o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private TakedownConfig() {}
}
