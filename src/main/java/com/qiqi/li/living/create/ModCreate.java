package com.qiqi.li.living.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.container.ContainerStressData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModCreate {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Create");

    public static final float BASE_RPM = 8.0f;
    public static final float BASE_SU_CAPACITY = 32.0f;

    private static boolean integrationChecked = false;
    private static boolean integrationAvailable = false;

    public static void init() {
        if (!CreateCompat.isLoaded()) return;
    }

    private static boolean isIntegrationAvailable() {
        if (!integrationChecked) {
            integrationChecked = true;
            try {
                Class.forName("com.qiqi.li.living.create.CreateIntegration");
                integrationAvailable = true;
                LOGGER.info("[ModCreate] CreateIntegration class available");
            } catch (ClassNotFoundException e) {
                integrationAvailable = false;
                LOGGER.warn("[ModCreate] CreateIntegration class NOT available, stress output disabled");
            }
        }
        return integrationAvailable;
    }

    public static void updateStressOutput(Level level, BlockPos containerPos, ContainerStressData stressData) {
        if (!CreateCompat.isLoaded()) return;
        if (!isIntegrationAvailable()) return;
        try {
            CreateIntegration.updateStressOutput(level, containerPos, stressData);
        } catch (NoClassDefFoundError e) {
            integrationAvailable = false;
            integrationChecked = false;
            LOGGER.error("[ModCreate] Failed to call CreateIntegration, disabling stress output", e);
        }
    }
}