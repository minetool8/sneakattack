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
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_TARGETS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WEAPON_BLACKLIST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("takedown");
        RANGE = b.comment("How close (blocks, measured to the zombie's body) the player must be for the prompt to appear.")
                .defineInRange("range", 2.0, 0.5, 4.0);
        CHARGE_TICKS = b.comment("Charge time in ticks (20 = 1 second). The weapon's wind-up is slowed to last exactly this long.")
                .defineInRange("chargeTicks", 24, 4, 200);
        MIN_WEAPON_DAMAGE = b.comment("A held item counts as a melee weapon when its main-hand attack damage bonus is at least this.",
                        "Bare hands and guns have none, swords/axes/tools and modded melee weapons do.")
                .defineInRange("minWeaponDamage", 2.0, 0.0, 100.0);
        REQUIRE_SNEAK_WHILE_CHARGING = b.comment("Cancel the charge if the player stops sneaking.")
                .define("requireSneakWhileCharging", false);
        INVESTIGATING_COUNTS_AS_AWARE = b.comment("With the zombieai mod: a zombie that is walking to a sound is NOT a valid victim.")
                .define("investigatingCountsAsAware", true);
        CALM_ZOMBIE_DURING_CHARGE = b.comment("Hold the zombie still and clear its target while the blow charges, so it cannot ruin the takedown.")
                .define("calmZombieDuringCharge", true);
        DAMAGE_WEAPON = b.comment("The weapon loses 1 durability per takedown (not in creative).")
                .define("damageWeapon", true);
        FOLLOW_THROUGH_TICKS = b.comment("Client: ticks after the blow lands before the (slowed) follow-through animation is faded out. 0 = let it play fully.")
                .defineInRange("followThroughTicks", 6, 0, 60);
        EXTRA_TARGETS = b.comment("Entity ids that can be taken down besides everything that extends the vanilla Zombie (husk, drowned, zombie villager...).")
                .defineListAllowEmpty("extraTargets", List.of(), o -> o instanceof String);
        WEAPON_BLACKLIST = b.comment("Item ids that never count as a melee weapon.")
                .defineListAllowEmpty("weaponBlacklist", List.of(), o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private TakedownConfig() {}
}
