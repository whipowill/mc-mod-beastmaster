package com.whipowill.beastmaster;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class BeastConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("BeastMaster");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "beastmaster.json");

    // Config fields
    public boolean petRegen = true;
    public boolean mountRegen = false;
    public boolean petImmortal = true;
    public boolean mountImmortal = true;
    public int healthRequiredToFight = 20;
    public int healthRequiredToMove = 20;
    public int whistleCooldownSeconds = 30;
    public boolean disableFriendlyFire = true;
    public String[] aggressivePets = {"minecraft:wolf"};
    public String[] supportedPetEntities = {
        "minecraft:wolf", "minecraft:cat", "minecraft:parrot"
    };
    public String[] supportedMountEntities = {
        "minecraft:horse", "minecraft:donkey", "minecraft:mule",
        "minecraft:llama", "minecraft:pig"
    };
    public boolean petsAttackHostileMobs = true;
    public int petAggressionRange = 12; // How far pets can detect hostile mobs
    public boolean enableWhistleSounds = true; // New config for whistle sounds

    private transient Set<String> petEntitySet = new HashSet<>();
    private transient Set<String> mountEntitySet = new HashSet<>();

    public static boolean isSupportedPet(Entity entity) {
        if (BeastMasterMod.CONFIG == null) return false;

        // Get entity registry name
        String entityId = EntityType.getId(entity.getType()).toString();

        // Check if it's in our supported pets list
        return BeastMasterMod.CONFIG.petEntitySet.contains(entityId) ||
               // Keep vanilla compatibility
               (entity instanceof WolfEntity) ||
               (entity instanceof CatEntity) ||
               (entity instanceof ParrotEntity);
    }

    public static boolean isSupportedMount(Entity entity) {
        if (BeastMasterMod.CONFIG == null) return false;

        String entityId = EntityType.getId(entity.getType()).toString();

        return BeastMasterMod.CONFIG.mountEntitySet.contains(entityId) ||
               // Keep vanilla compatibility with tamed checks
               (entity instanceof HorseEntity && ((HorseEntity) entity).isTame()) ||
               (entity instanceof DonkeyEntity && ((DonkeyEntity) entity).isTame()) ||
               (entity instanceof MuleEntity && ((MuleEntity) entity).isTame()) ||
               (entity instanceof LlamaEntity && ((LlamaEntity) entity).isTame()) ||
               (entity instanceof PigEntity && ((PigEntity) entity).isSaddled());
    }

    // NEW: Helper method to check if a pet should be aggressive
    public static boolean shouldPetBeAggressive(Entity entity) {
        if (BeastMasterMod.CONFIG == null || !BeastMasterMod.CONFIG.petsAttackHostileMobs) {
            return false;
        }

        String entityId = EntityType.getId(entity.getType()).toString();

        // Check if this entity is in the aggressive pets list
        for (String aggressivePet : BeastMasterMod.CONFIG.aggressivePets) {
            if (aggressivePet.equals(entityId)) {
                return true;
            }
        }

        return false;
    }

    public static BeastConfig load() {
        BeastConfig config = new BeastConfig();

        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                config = GSON.fromJson(json, BeastConfig.class);
            } else {
                // Create directory if it doesn't exist
                Files.createDirectories(CONFIG_PATH.getParent());
            }

            // Initialize sets
            config.petEntitySet = new HashSet<>(Arrays.asList(config.supportedPetEntities));
            config.mountEntitySet = new HashSet<>(Arrays.asList(config.supportedMountEntities));

            // Save config to ensure any new fields are persisted
            config.save();

            LOGGER.info("Loaded Leader of the Pack configuration");
        } catch (Exception e) {
            LOGGER.error("Failed to load config, using defaults", e);
        }

        return config;
    }

    public void save() {
        try {
            String json = GSON.toJson(this);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    public static void addPetEntity(String entityId) {
        if (BeastMasterMod.CONFIG != null && !BeastMasterMod.CONFIG.petEntitySet.contains(entityId)) {
            BeastMasterMod.CONFIG.petEntitySet.add(entityId);
            // Update the array for saving
            BeastMasterMod.CONFIG.supportedPetEntities = BeastMasterMod.CONFIG.petEntitySet.toArray(new String[0]);
            BeastMasterMod.CONFIG.save();
        }
    }

    public static void addMountEntity(String entityId) {
        if (BeastMasterMod.CONFIG != null && !BeastMasterMod.CONFIG.mountEntitySet.contains(entityId)) {
            BeastMasterMod.CONFIG.mountEntitySet.add(entityId);
            BeastMasterMod.CONFIG.supportedMountEntities = BeastMasterMod.CONFIG.mountEntitySet.toArray(new String[0]);
            BeastMasterMod.CONFIG.save();
        }
    }
}
