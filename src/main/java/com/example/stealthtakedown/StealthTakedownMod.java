package com.example.stealthtakedown;

import com.example.stealthtakedown.config.TakedownConfig;
import com.example.stealthtakedown.net.TakedownNet;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(StealthTakedownMod.MODID)
public class StealthTakedownMod {
    public static final String MODID = "stealthtakedown";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StealthTakedownMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TakedownConfig.SPEC, "stealthtakedown-common.toml");
        TakedownNet.init();
    }
}
