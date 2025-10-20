package com.whipowill.beastmaster;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.DonkeyEntity;
import net.minecraft.entity.passive.MuleEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;

public class BeastMasterMod implements ModInitializer {
    public static final String MOD_ID = "beastmaster";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static BeastConfig CONFIG;

    // Cooldown tracking
    private static final Map<UUID, Long> playerWhistleCooldowns = new HashMap<>();
    private final Map<UUID, Long> mountBuckCooldowns = new HashMap<>();
    
    // Dead pet registry - persists forever to prevent resurrection
    private static final Set<UUID> globalDeadEntityRegistry = new HashSet<>();
    
    // Whistle sounds
    public static final Identifier WHISTLE_1_ID = new Identifier(MOD_ID, "whistle1");
    public static final Identifier WHISTLE_2_ID = new Identifier(MOD_ID, "whistle2");
    public static final Identifier WHISTLE_3_ID = new Identifier(MOD_ID, "whistle3");
    public static SoundEvent WHISTLE_1_EVENT;
    public static SoundEvent WHISTLE_2_EVENT;
    public static SoundEvent WHISTLE_3_EVENT;


    // Interaction debouncing
    private static final Map<UUID, Long> lastInteractionSave = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Leader of the Pack mod initialized!");

        // Load configuration
        CONFIG = BeastConfig.load();
        
        // Load dead entity registry
        loadDeadEntityRegistry();
        
        // Register whistle sounds
        WHISTLE_1_EVENT = new SoundEvent(WHISTLE_1_ID);
        WHISTLE_2_EVENT = new SoundEvent(WHISTLE_2_ID);
        WHISTLE_3_EVENT = new SoundEvent(WHISTLE_3_ID);
        
        Registry.register(Registry.SOUND_EVENT, WHISTLE_1_ID, WHISTLE_1_EVENT);
        Registry.register(Registry.SOUND_EVENT, WHISTLE_2_ID, WHISTLE_2_EVENT);
        Registry.register(Registry.SOUND_EVENT, WHISTLE_3_ID, WHISTLE_3_EVENT);

        // Register commands
        BeastCommand.register();

        // Register entity tracking on load
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (isSupportedEntity(entity) && isOwned(entity) && getOwnerUuid(entity) != null) {
                // Just track it - we'll handle removal during dimension changes
                PackManager manager = PackManager.get((ServerWorld) world);
                manager.storeEntityNbt(entity);
            }
        });

        // OPTIMIZED: Different systems at different frequencies with better performance
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            long serverTime = server.getTicks();

            // Clean up cooldowns every 2 minutes (less frequent)
            if (serverTime % 2400 == 0) {
                cleanUpCooldowns(server);
            }

            // Aggression processing: Every 2 seconds (40 ticks) - using cached registry
            if (serverTime % 40 == 0) {
                for (ServerWorld world : server.getWorlds()) {
                    List<ServerPlayerEntity> players = world.getPlayers();
                    if (players.isEmpty()) continue;
                    
                    PackManager manager = PackManager.get(world);
                    double searchRadiusSq = 64.0 * 64.0;
                    
                    for (ServerPlayerEntity player : players) {
                        // Get ONLY this player's pets from the cached registry
                        List<PackManager.EntityData> playerPets = manager.getPetsByOwner(player.getUuid());
                        if (playerPets.isEmpty()) continue;
                        
                        for (PackManager.EntityData petData : playerPets) {
                            if (!petData.isAlive) continue;
                            
                            // Find the actual entity (much cheaper than world scan)
                            Entity pet = world.getEntity(petData.entityUuid);
                            if (pet == null || !pet.isAlive() || !(pet instanceof LivingEntity living)) continue;
                            
                            // Check distance using quick squared distance check
                            if (pet.squaredDistanceTo(player) > searchRadiusSq) continue;
                            
                            // Apply aggressive behavior for pets
                            if (CONFIG != null && CONFIG.petsAttackHostileMobs) {
                                try {
                                    applyAggressiveBehavior(living);
                                } catch (Exception e) {
                                    LOGGER.error("Error applying aggressive behavior to {}", pet.getUuid(), e);
                                }
                            }
                        }
                    }
                }
            }

            // Regeneration processing: Every 10 seconds (200 ticks) - using cached registry
            if (serverTime % 200 == 0) {
                for (ServerWorld world : server.getWorlds()) {
                    List<ServerPlayerEntity> players = world.getPlayers();
                    if (players.isEmpty()) continue;
                    
                    PackManager manager = PackManager.get(world);
                    double searchRadiusSq = 64.0 * 64.0;
                    
                    for (ServerPlayerEntity player : players) {
                        // Get ALL this player's entities (pets + mounts) from cached registry
                        List<PackManager.EntityData> playerEntities = manager.getEntitiesByOwner(player.getUuid());
                        if (playerEntities.isEmpty()) continue;
                        
                        for (PackManager.EntityData entityData : playerEntities) {
                            if (!entityData.isAlive) continue;
                            
                            // Find the actual entity
                            Entity entity = world.getEntity(entityData.entityUuid);
                            if (entity == null || !entity.isAlive() || !(entity instanceof LivingEntity living)) continue;
                            
                            // Check distance
                            if (entity.squaredDistanceTo(player) > searchRadiusSq) continue;
                            
                            boolean isPet = entityData.isPet;
                            boolean isMount = !entityData.isPet;

                            // Apply regeneration effects
                            if (CONFIG != null && ((isPet && CONFIG.petRegen) || (isMount && CONFIG.mountRegen))) {
                                try {
                                    applyRegenEffects(living);
                                } catch (Exception e) {
                                    LOGGER.error("Error applying regeneration effects to {}", entity.getUuid(), e);
                                }
                            }
                        }
                    }
                }
            }

            // Fast mount saving: Every 10 seconds (200 ticks) - for inventory/armor changes on nearby mounts
            if (serverTime % 200 == 0) {
                for (ServerWorld world : server.getWorlds()) {
                    List<ServerPlayerEntity> players = world.getPlayers();
                    if (players.isEmpty()) continue;
                    
                    PackManager manager = PackManager.get(world);
                    double saveRadiusSq = 16.0 * 16.0; // Slightly larger radius for interaction safety
                    
                    for (ServerPlayerEntity player : players) {
                        // Get ONLY this player's mounts from cached registry
                        List<PackManager.EntityData> playerMounts = manager.getMountsByOwner(player.getUuid());
                        if (playerMounts.isEmpty()) continue;
                        
                        for (PackManager.EntityData mountData : playerMounts) {
                            if (!mountData.isAlive) continue;
                            
                            // Find the actual mount
                            Entity mount = world.getEntity(mountData.entityUuid);
                            if (mount == null || !mount.isAlive()) continue;
                            
                            // Check if mount is near player (interaction range)
                            if (mount.squaredDistanceTo(player) > saveRadiusSq) continue;
                            
                            try {
                                // ALWAYS save nearby mounts - inventory/armor changes are important!
                                manager.storeEntityNbt(mount);
                                LOGGER.debug("Fast-saved nearby mount: {}", mount.getUuid());
                            } catch (Exception e) {
                                LOGGER.error("Error in fast mount save", e);
                            }
                        }
                    }
                }
            }

            // Slow mount saving: Every 60 seconds (1200 ticks) - for all mounts (backup)
            if (serverTime % 1200 == 0) {
                for (ServerWorld world : server.getWorlds()) {
                    List<ServerPlayerEntity> players = world.getPlayers();
                    if (players.isEmpty()) continue;
                    
                    PackManager manager = PackManager.get(world);
                    double saveRadiusSq = 32.0 * 32.0; // Larger radius for backup saves
                    
                    for (ServerPlayerEntity player : players) {
                        // Get ONLY this player's mounts from cached registry
                        List<PackManager.EntityData> playerMounts = manager.getMountsByOwner(player.getUuid());
                        if (playerMounts.isEmpty()) continue;
                        
                        for (PackManager.EntityData mountData : playerMounts) {
                            if (!mountData.isAlive) continue;
                            
                            // Find the actual mount
                            Entity mount = world.getEntity(mountData.entityUuid);
                            if (mount == null || !mount.isAlive()) continue;
                            
                            // Check if mount is near player
                            if (mount.squaredDistanceTo(player) > saveRadiusSq) continue;
                            
                            try {
                                // Only save if entity has moved significantly (performance optimization)
                                Optional<PackManager.EntityData> existingData = manager.getEntityData(mount.getUuid());
                                if (existingData.isPresent()) {
                                    PackManager.EntityData oldData = existingData.get();
                                    // Use squared distance for performance
                                    double dx = mount.getX() - oldData.x;
                                    double dy = mount.getY() - oldData.y;
                                    double dz = mount.getZ() - oldData.z;
                                    double distanceMovedSq = dx*dx + dy*dy + dz*dz;

                                    if (distanceMovedSq > 4.0) { // 2.0 squared
                                        manager.storeEntityNbt(mount);
                                    }
                                } else {
                                    manager.storeEntityNbt(mount);
                                }
                            } catch (Exception e) {
                                LOGGER.error("Error in slow mount save", e);
                            }
                        }
                    }
                }
            }

            // Dead entity cleanup: Every 5 minutes (6000 ticks) - much less frequent
            if (serverTime % 6000 == 0) {
                int totalCleaned = 0;
                int totalDeleted = 0;
                int totalNbtCleared = 0;
                
                for (ServerWorld world : server.getWorlds()) {
                    PackManager manager = PackManager.get(world);
                    List<UUID> toRemove = new ArrayList<>();

                    // Check all tracked entities in this world
                    for (PackManager.EntityData data : manager.getAllEntities()) {
                        UUID entityUuid = data.entityUuid;
                        
                        // Check if entity is in global dead registry
                        if (isEntityDeadGlobally(entityUuid)) {
                            // Mark as dead and clear NBT data instead of removing immediately
                            manager.markEntityAsDead(entityUuid);
                            totalNbtCleared++;
                            LOGGER.debug("Cleared NBT data for globally dead entity: {} in {}", entityUuid, world.getRegistryKey().getValue());
                            continue;
                        }
                        
                        // Check if entity is marked as dead OR if loaded entity is dead
                        boolean shouldMarkDead = false;
                        boolean shouldMarkGlobal = false;
                        
                        // Check if marked as dead in this world
                        if (!data.isAlive) {
                            shouldMarkDead = true;
                            shouldMarkGlobal = true;
                            LOGGER.debug("Cleaning up dead pet (marked dead): {} in {}", entityUuid, world.getRegistryKey().getValue());
                        } else {
                            // Check if loaded entity is actually dead
                            Entity entity = world.getEntity(entityUuid);
                            if (entity != null && !entity.isAlive()) {
                                shouldMarkDead = true;
                                shouldMarkGlobal = true;
                                LOGGER.debug("Cleaning up dead pet (loaded dead): {} in {}", entityUuid, world.getRegistryKey().getValue());
                            }
                        }
                        
                        if (shouldMarkDead) {
                            // Mark as dead and clear NBT data
                            manager.markEntityAsDead(entityUuid);
                            totalNbtCleared++;
                            if (shouldMarkGlobal) {
                                markEntityAsDeadGlobally(entityUuid);
                            }
                        }
                    }

                    // Remove entities that have been dead for a long time (clean up completely)
                    // Keep them for a while to prevent resurrection, then remove entirely
                    long now = System.currentTimeMillis();
                    for (PackManager.EntityData data : manager.getAllEntities()) {
                        if (!data.isAlive && (now - data.timestamp) > 86400000) { // 24 hours
                            toRemove.add(data.entityUuid);
                        }
                    }

                    // Remove old dead entities from tracking
                    for (UUID deadEntity : toRemove) {
                        manager.untrackEntity(deadEntity);
                        totalCleaned++;
                    }
                    
                    // Aggressive cleanup: Delete any loaded entities that are in the dead registry
                    for (UUID deadUuid : globalDeadEntityRegistry) {
                        Entity deadEntity = world.getEntity(deadUuid);
                        if (deadEntity != null && deadEntity.isAlive()) {
                            deadEntity.remove(Entity.RemovalReason.DISCARDED);
                            totalDeleted++;
                            LOGGER.info("Deleted globally dead entity that was still loaded: {} in {}", deadUuid, world.getRegistryKey().getValue());
                        }
                    }
                }

                if (totalCleaned > 0 || totalDeleted > 0 || totalNbtCleared > 0) {
                    LOGGER.info("Cleaned up {} old dead entities, deleted {} loaded dead entities, cleared NBT for {} dead entities", 
                        totalCleaned, totalDeleted, totalNbtCleared);
                }
            }
        });

        // Removed automatic pet teleportation when players change dimensions
        // Players can manually summon pets using the whistle commands

        // DEBOUNCED: Save on interactions (only once per second per entity)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && isSupportedEntity(entity)) {
                if (isOwnedByPlayer(entity, player.getUuid())) {
                    world.getServer().execute(() -> {
                        try {
                            UUID entityId = entity.getUuid();
                            long now = System.currentTimeMillis();

                            // Only save once per second per entity
                            Long lastSave = lastInteractionSave.get(entityId);
                            if (lastSave == null || now - lastSave > 1000) {
                                PackManager manager = PackManager.get((ServerWorld) world);
                                manager.storeEntityNbt(entity);
                                lastInteractionSave.put(entityId, now);
                                LOGGER.debug("Updated entity on interaction: {}", entity.getUuid());
                            }
                        } catch (Exception e) {
                            LOGGER.error("Error tracking entity on interaction", e);
                        }
                    });
                }
            }
            return ActionResult.PASS;
        });
    }

    private void applyRegenEffects(LivingEntity entity) {
        if (CONFIG == null || entity == null || !entity.isAlive()) {
            return;
        }
        
        try {
            boolean isPet = BeastConfig.isSupportedPet(entity);
            boolean isMount = BeastConfig.isSupportedMount(entity);

            if (isPet && CONFIG.petRegen) {
                if (entity.getHealth() < entity.getMaxHealth()) {
                    entity.heal(1.0F);
                }
            }

            if (isMount && CONFIG.mountRegen) {
                if (entity.getHealth() < entity.getMaxHealth()) {
                    entity.heal(1.0F);
                }
            }

            // Apply combat behavior for pets
            if (isPet && CONFIG.petImmortal) {
                clearAggressionForLowHealth(entity);
            }

            // Apply riding behavior for mounts - BUCK PLAYER OFF when injured!
            if (isMount && CONFIG.mountImmortal) {
                float healthPercent = (entity.getHealth() / entity.getMaxHealth()) * 100;
                if (healthPercent <= CONFIG.healthRequiredToMove) {
                    buckPlayerOff(entity);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error applying regeneration effects to entity {}", entity != null ? entity.getUuid() : "null", e);
        }
    }

    // New method to buck player off injured mount
    private void buckPlayerOff(Entity entity) {
        try {
            if (entity.hasPassengers()) {
                UUID mountId = entity.getUuid();
                long now = System.currentTimeMillis();

                // Only buck every 2 seconds to avoid spam, but be persistent
                Long lastBuck = mountBuckCooldowns.get(mountId);
                if (lastBuck != null && now - lastBuck < 2000) {
                    return; // Still on cooldown
                }

                // Get all passengers and dismount them
                List<Entity> passengers = entity.getPassengerList();
                for (Entity passenger : passengers) {
                    if (passenger instanceof PlayerEntity) {
                        passenger.stopRiding();
                        mountBuckCooldowns.put(mountId, now); // Set cooldown
                        LOGGER.debug("Bucked player off injured mount: {}", entity.getUuid());

                        // Send message to player (first time or occasionally)
                        if (passenger instanceof ServerPlayerEntity player) {
                            if (lastBuck == null || now - lastBuck > 10000) { // Only message every 10 seconds
                                player.sendMessage(Text.of("§cYour mount is too injured to carry you!"), false);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error bucking player off mount", e);
        }
    }

    // Cooldown management methods
    public static boolean isPlayerOnCooldown(UUID playerUUID) {
        if (CONFIG.whistleCooldownSeconds <= 0) return false;

        Long lastUsed = playerWhistleCooldowns.get(playerUUID);
        if (lastUsed == null) return false;

        long cooldownMillis = CONFIG.whistleCooldownSeconds * 1000L;
        return System.currentTimeMillis() - lastUsed < cooldownMillis;
    }

    public static void setPlayerCooldown(UUID playerUUID) {
        if (CONFIG.whistleCooldownSeconds > 0) {
            playerWhistleCooldowns.put(playerUUID, System.currentTimeMillis());
            // Limit map size to prevent memory leaks
            if (playerWhistleCooldowns.size() > 1000) {
                // Clean up will happen on the next scheduled cleanup tick
            }
        }
    }

    public static long getCooldownRemaining(UUID playerUUID) {
        if (CONFIG.whistleCooldownSeconds <= 0) return 0;

        Long lastUsed = playerWhistleCooldowns.get(playerUUID);
        if (lastUsed == null) return 0;

        long elapsed = System.currentTimeMillis() - lastUsed;
        long cooldownMillis = CONFIG.whistleCooldownSeconds * 1000L;
        return Math.max(0, cooldownMillis - elapsed);
    }

    // Helper method to play random whistle sound
    public static void playRandomWhistleSound(ServerWorld world, ServerPlayerEntity player) {
        if (CONFIG == null || !CONFIG.enableWhistleSounds) {
            return;
        }
        
        try {
            SoundEvent[] whistleSounds = {WHISTLE_1_EVENT, WHISTLE_2_EVENT, WHISTLE_3_EVENT};
            int randomIndex = world.random.nextInt(whistleSounds.length);
            SoundEvent selectedSound = whistleSounds[randomIndex];
            
            // Play sound to all players nearby with proper volume and pitch
            world.playSound(
                null, 
                player.getBlockPos(), 
                selectedSound, 
                player.getSoundCategory(), 
                0.8f,  // Volume
                0.9f + world.random.nextFloat() * 0.2f  // Pitch variation
            );
        } catch (Exception e) {
            LOGGER.error("Error playing whistle sound", e);
        }
    }

    private void cleanUpCooldowns(MinecraftServer server) {
        if (CONFIG == null || server == null) return;
        
        long now = System.currentTimeMillis();
        long cooldownMillis = CONFIG.whistleCooldownSeconds * 1000L;

        // Clean up old whistle cooldowns (keep for 1 minute after cooldown expires)
        playerWhistleCooldowns.entrySet().removeIf(entry ->
            now - entry.getValue() > cooldownMillis + 60000);

        // Clean up mount buck cooldowns (keep for 30 seconds after last buck)
        mountBuckCooldowns.entrySet().removeIf(entry ->
            now - entry.getValue() > 30000);

        // Clean up interaction save cooldowns (keep for 5 minutes)
        lastInteractionSave.entrySet().removeIf(entry ->
            now - entry.getValue() > 300000);
            
    }
    
    // Dead entity registry management
    private void loadDeadEntityRegistry() {
        try {
            Path deadRegistryPath = Paths.get("config", "beastmaster_dead_entities.txt");
            if (Files.exists(deadRegistryPath)) {
                List<String> lines = Files.readAllLines(deadRegistryPath);
                for (String line : lines) {
                    try {
                        UUID deadUuid = UUID.fromString(line.trim());
                        globalDeadEntityRegistry.add(deadUuid);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid UUID in dead registry: {}", line);
                    }
                }
                LOGGER.info("Loaded {} dead entities from registry", globalDeadEntityRegistry.size());
            }
        } catch (Exception e) {
            LOGGER.error("Error loading dead entity registry", e);
        }
    }
    
    private void saveDeadEntityRegistry() {
        try {
            Path deadRegistryPath = Paths.get("config", "beastmaster_dead_entities.txt");
            Files.createDirectories(deadRegistryPath.getParent());
            
            List<String> lines = new ArrayList<>();
            for (UUID deadUuid : globalDeadEntityRegistry) {
                lines.add(deadUuid.toString());
            }
            
            Files.write(deadRegistryPath, lines);
            LOGGER.debug("Saved {} dead entities to registry", globalDeadEntityRegistry.size());
        } catch (Exception e) {
            LOGGER.error("Error saving dead entity registry", e);
        }
    }
    
    public static void markEntityAsDeadGlobally(UUID entityUuid) {
        if (!globalDeadEntityRegistry.contains(entityUuid)) {
            globalDeadEntityRegistry.add(entityUuid);
            // Save immediately to ensure persistence
            BeastMasterMod instance = new BeastMasterMod(); // Temporary instance for saving
            instance.saveDeadEntityRegistry();
            LOGGER.info("Marked entity as permanently dead: {}", entityUuid);
        }
    }
    
    public static boolean isEntityDeadGlobally(UUID entityUuid) {
        return globalDeadEntityRegistry.contains(entityUuid);
    }

    // Helper method to check if entity is supported
    public static boolean isSupportedEntity(Entity entity) {
        return BeastConfig.isSupportedPet(entity) || BeastConfig.isSupportedMount(entity);
    }

    // Helper method to check if entity is owned
    public static boolean isOwned(Entity entity) {
        return getOwnerUuid(entity) != null;
    }

    // Helper method to check if entity is owned by specific player
    public static boolean isOwnedByPlayer(Entity entity, UUID playerUUID) {
        if (entity instanceof Tameable) {
            Tameable tameable = (Tameable) entity;
            return tameable.getOwnerUuid() != null && tameable.getOwnerUuid().equals(playerUUID);
        }
        // Use the same logic as the working mount mod:
        if (entity instanceof HorseEntity horse) {
            return horse.isTame() && horse.getOwnerUuid() != null && horse.getOwnerUuid().equals(playerUUID);
        }
        if (entity instanceof DonkeyEntity donkey) {
            return donkey.isTame() && donkey.getOwnerUuid() != null && donkey.getOwnerUuid().equals(playerUUID);
        }
        if (entity instanceof MuleEntity mule) {
            return mule.isTame() && mule.getOwnerUuid() != null && mule.getOwnerUuid().equals(playerUUID);
        }
        if (entity instanceof LlamaEntity llama) {
            return llama.isTame() && llama.getOwnerUuid() != null && llama.getOwnerUuid().equals(playerUUID);
        }
        // For pigs with saddles
        if (entity instanceof PigEntity pig && pig.isSaddled() && pig.getFirstPassenger() instanceof PlayerEntity player) {
            return player.getUuid().equals(playerUUID);
        }
        return false;
    }

    // Helper method to get owner UUID
    public static UUID getOwnerUuid(Entity entity) {
        if (entity instanceof Tameable) {
            Tameable tameable = (Tameable) entity;
            return tameable.getOwnerUuid();
        }
        // For mounts
        if (entity instanceof HorseEntity horse) {
            return horse.getOwnerUuid();
        }
        if (entity instanceof DonkeyEntity donkey) {
            return donkey.getOwnerUuid();
        }
        if (entity instanceof MuleEntity mule) {
            return mule.getOwnerUuid();
        }
        if (entity instanceof LlamaEntity llama) {
            return llama.getOwnerUuid();
        }
        // For pigs, use the rider as owner
        if (entity instanceof PigEntity pig && pig.isSaddled() && pig.getFirstPassenger() instanceof PlayerEntity player) {
            return player.getUuid();
        }
        return null;
    }

    // Utility method to clear aggression when health is below threshold
    public static void clearAggressionForLowHealth(LivingEntity entity) {
        if (CONFIG == null || entity == null || !entity.isAlive()) return;
        
        float healthPercent = (entity.getHealth() / entity.getMaxHealth()) * 100;
        if (healthPercent > CONFIG.healthRequiredToFight) return;
        
        // Clear target if health is too low for ALL mob entities
        if (entity instanceof MobEntity mob) {
            mob.setTarget(null);
            // Clear anger and sitting pose for wolves
            if (entity instanceof WolfEntity wolf) {
                wolf.setAngryAt(null);
                wolf.setInSittingPose(false);
            }
            // Clear aggression for cats
            if (entity instanceof CatEntity cat) {
                cat.setInSittingPose(false);
            }
            // Clear aggression for parrots
            if (entity instanceof ParrotEntity parrot) {
                parrot.setInSittingPose(false);
            }
            // Clear sitting pose for any other tameable mob
            if (entity instanceof net.minecraft.entity.passive.TameableEntity tameable) {
                tameable.setInSittingPose(false);
            }
        }
    }

    // New method for aggressive pet behavior - configurable by pet type
    private void applyAggressiveBehavior(LivingEntity pet) {
        try {
            if (CONFIG == null || !CONFIG.petsAttackHostileMobs) return;

            // Check if this specific pet type should be aggressive
            if (!BeastConfig.shouldPetBeAggressive(pet)) {
                return;
            }

            // Only apply if pet is healthy enough to fight
            clearAggressionForLowHealth(pet);
            float healthPercent = (pet.getHealth() / pet.getMaxHealth()) * 100;
            if (healthPercent <= CONFIG.healthRequiredToFight) {
                return;
            }

            // Check if pet is already targeting something (only for MobEntity)
            if (pet instanceof MobEntity mobPet && mobPet.getTarget() != null && mobPet.getTarget().isAlive()) {
                return;
            }

            // Get the pet's owner
            UUID ownerUuid = getOwnerUuid(pet);
            if (ownerUuid == null) return;

            // Find owner in the same world
            PlayerEntity owner = pet.getWorld().getPlayerByUuid(ownerUuid);
            if (owner == null) return;

            // Find mobs that are targeting the owner AND are visible
            List<LivingEntity> threatsToOwner = pet.getWorld().getEntitiesByClass(
                LivingEntity.class,
                owner.getBoundingBox().expand(CONFIG.petAggressionRange),
                threat -> isThreatToOwner(threat, owner) && canPetSeeTarget(pet, threat)
            );

            // Attack the closest visible threat to owner
            LivingEntity closestThreat = null;
            double closestDistance = Double.MAX_VALUE;

            for (LivingEntity threat : threatsToOwner) {
                double distance = pet.squaredDistanceTo(threat);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestThreat = threat;
                }
            }

            // Set the pet to attack the threat (only if pet is a MobEntity)
            if (closestThreat != null && pet instanceof MobEntity mob) {
                mob.setTarget(closestThreat);

                // Special handling for wolves to make them angry
                if (pet instanceof WolfEntity wolf) {
                    wolf.setAngryAt(closestThreat.getUuid());
                }

                LOGGER.debug("Aggressive pet {} defending owner from {}", pet.getUuid(), closestThreat.getType());
            }

        } catch (Exception e) {
            LOGGER.error("Error applying aggressive behavior to pet", e);
        }
    }

    private boolean isThreatToOwner(LivingEntity entity, PlayerEntity owner) {
        // Skip if it's a pet or mount
        if (BeastConfig.isSupportedPet(entity) || BeastConfig.isSupportedMount(entity)) {
            return false;
        }

        // Skip players
        if (entity instanceof PlayerEntity) {
            return false;
        }

        // Check if this entity is targeting the owner (only for MobEntity)
        if (entity instanceof MobEntity mob) {
            return owner.equals(mob.getTarget());
        }

        return false;
    }

    // Check if pet can actually see the target (no walls in between)
    private boolean canPetSeeTarget(LivingEntity pet, LivingEntity target) {
        // Simple distance check first
        if (pet.squaredDistanceTo(target) > (CONFIG.petAggressionRange * CONFIG.petAggressionRange)) {
            return false;
        }

        // Use Minecraft's built-in visibility check
        return pet.canSee(target);
    }

    // Helper method to find entity in any world
    public static Entity findEntityInAnyWorld(MinecraftServer server, UUID entityUuid) {
        if (server == null) return null;
        try {
            for (ServerWorld world : server.getWorlds()) {
                // Use getEntityLookup for better performance
                Entity entity = world.getEntity(entityUuid);
                if (entity != null && entity.isAlive()) {
                    return entity;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error in findEntityInAnyWorld", e);
        }
        return null;
    }


    private MinecraftServer getServer() {
        // This is a placeholder - you'll need to track the server instance
        // For now, return null and handle in cleanup
        return null;
    }

    // Make these public static so BeastCommand can use them
    public static List<PackManager.EntityData> getAllRegisteredPets(MinecraftServer server, UUID playerUUID) {
        Map<UUID, PackManager.EntityData> uniquePets = new HashMap<>();
        try {
            for (ServerWorld world : server.getWorlds()) {
                PackManager manager = PackManager.get(world);
                List<PackManager.EntityData> worldPets = manager.getPetsByOwner(playerUUID);
                for (PackManager.EntityData pet : worldPets) {
                    PackManager.EntityData existing = uniquePets.get(pet.entityUuid);
                    if (existing == null || pet.timestamp > existing.timestamp) {
                        uniquePets.put(pet.entityUuid, pet);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error getting all callable pets", e);
        }
        return new ArrayList<>(uniquePets.values());
    }

    public static List<PackManager.EntityData> getAllRegisteredMounts(MinecraftServer server, UUID playerUUID) {
        Map<UUID, PackManager.EntityData> uniqueMounts = new HashMap<>();
        try {
            for (ServerWorld world : server.getWorlds()) {
                PackManager manager = PackManager.get(world);
                List<PackManager.EntityData> worldMounts = manager.getMountsByOwner(playerUUID);
                for (PackManager.EntityData mount : worldMounts) {
                    PackManager.EntityData existing = uniqueMounts.get(mount.entityUuid);
                    if (existing == null || mount.timestamp > existing.timestamp) {
                        uniqueMounts.put(mount.entityUuid, mount);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error getting all callable mounts", e);
        }
        return new ArrayList<>(uniqueMounts.values());
    }

    public static Entity loadAndTeleportEntity(MinecraftServer server, PackManager.EntityData entityData, ServerPlayerEntity player) {
        // Copy the implementation from BeastCommand.java and make it public static
        try {
            LOGGER.info("=== SUMMONING ENTITY FROM NBT STORAGE ===");
            LOGGER.info("Entity: {}", entityData.entityUuid);

            ServerWorld targetWorld = (ServerWorld) player.getWorld();
            UUID entityUuid = entityData.entityUuid;

            // CRITICAL FIX: Check global dead registry first
            if (isEntityDeadGlobally(entityUuid)) {
                LOGGER.warn("Entity {} is in global dead registry. Cannot summon.", entityUuid);
                // Clean up from all tracking
                for (ServerWorld world : server.getWorlds()) {
                    PackManager manager = PackManager.get(world);
                    manager.untrackEntity(entityUuid);
                }
                return null;
            }

            // Check if entity is marked as dead in ANY world before proceeding
            boolean isEntityDead = false;
            for (ServerWorld world : server.getWorlds()) {
                PackManager manager = PackManager.get(world);
                Optional<PackManager.EntityData> data = manager.getEntityData(entityUuid);
                if (data.isPresent() && !data.get().isAlive) {
                    isEntityDead = true;
                    LOGGER.warn("Entity {} is marked as DEAD in world {}. Cannot summon.", entityUuid, world.getRegistryKey().getValue());
                    break;
                }
            }
            
            if (isEntityDead) {
                // Mark as globally dead and clean up
                markEntityAsDeadGlobally(entityUuid);
                for (ServerWorld world : server.getWorlds()) {
                    PackManager manager = PackManager.get(world);
                    manager.untrackEntity(entityUuid);
                    
                    // Also remove any loaded dead entity
                    Entity existingEntity = world.getEntity(entityUuid);
                    if (existingEntity != null && !existingEntity.isAlive()) {
                        existingEntity.remove(Entity.RemovalReason.DISCARDED);
                    }
                }
                return null;
            }

            // Remove from loaded worlds
            for (ServerWorld world : server.getWorlds()) {
                Entity existingEntity = world.getEntity(entityUuid);
                if (existingEntity != null && existingEntity.isAlive()) {
                    LOGGER.info("Removing entity from loaded world: {}", world.getRegistryKey().getValue());
                    existingEntity.remove(Entity.RemovalReason.DISCARDED);
                }

                // Remove from tracking in EVERY world
                PackManager manager = PackManager.get(world);
                if (manager.isEntityTracked(entityUuid)) {
                    manager.untrackEntity(entityUuid);
                }
            }

            if (entityData.entityNbt == null) {
                LOGGER.warn("No NBT data stored for entity {}. Cannot summon.", entityUuid);
                return null;
            }

            // Create new entity in target dimension
            LOGGER.info("Creating new entity in target dimension from NBT");
            Entity newEntity = EntityType.getEntityFromNbt(entityData.entityNbt, targetWorld).orElse(null);
            if (newEntity == null) {
                LOGGER.error("Failed to create entity from NBT data");
                return null;
            }

            // Update dimension in NBT to match target world
            NbtCompound newNbt = newEntity.writeNbt(new NbtCompound());
            newNbt.putString("Dimension", targetWorld.getRegistryKey().getValue().toString());
            newEntity.readNbt(newNbt);

            // Position in target world
            newEntity.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
            LOGGER.info("Spawning entity at position: {}, {}, {}", player.getX(), player.getY(), player.getZ());
            targetWorld.spawnEntity(newEntity);

            LOGGER.info("Successfully summoned entity to dimension: {}", targetWorld.getRegistryKey().getValue());

            // Update tracking ONLY in target world
            PackManager manager = PackManager.get(targetWorld);
            manager.storeEntityNbt(newEntity);

            LOGGER.info("=== SUMMONING COMPLETE ===");
            return newEntity;

        } catch (Exception e) {
            LOGGER.error("Error summoning entity from NBT storage", e);
        }
        return null;
    }
}
