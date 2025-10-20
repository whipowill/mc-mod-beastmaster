package com.whipowill.beastmaster;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class BeastCommand implements Command<ServerCommandSource> {

    private static final Logger LOGGER = LoggerFactory.getLogger("BeastMaster");

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("beast")
                .then(CommandManager.literal("mount")
                    .then(CommandManager.literal("whistle")
                        .executes(context -> callAllMounts(context))
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(context -> callMountsByName(context))))
                    .then(CommandManager.literal("find")
                        .executes(context -> findMounts(context)))
                    .then(CommandManager.literal("list")
                        .executes(context -> listMounts(context)))
                    .then(CommandManager.literal("debug")
                        .executes(context -> debugMounts(context)))
                    .then(CommandManager.literal("setfree")
                        .then(CommandManager.argument("mountName", StringArgumentType.greedyString())
                            .executes(context -> setFreeMount(context))))
                    .then(CommandManager.literal("dismiss")
                        .then(CommandManager.argument("mountName", StringArgumentType.greedyString())
                            .executes(context -> dismissMount(context)))))
                .then(CommandManager.literal("pet")
                    .then(CommandManager.literal("whistle")
                        .executes(context -> callAllPets(context))
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(context -> callPetsByName(context))))
                    .then(CommandManager.literal("sit")
                        .executes(context -> executePetSit(context.getSource())))
                    .then(CommandManager.literal("follow")
                        .executes(context -> executePetFollow(context.getSource(), -1)) // -1 means all pets
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                            .executes(context -> executePetFollow(context.getSource(),
                                IntegerArgumentType.getInteger(context, "count")))))
                    .then(CommandManager.literal("find")
                        .executes(context -> findPets(context)))
                    .then(CommandManager.literal("list")
                        .executes(context -> listPets(context)))
                    .then(CommandManager.literal("debug")
                        .executes(context -> debugPets(context)))
                    .then(CommandManager.literal("setfree")
                        .then(CommandManager.argument("petName", StringArgumentType.greedyString())
                            .executes(context -> setFreePet(context))))
                    .then(CommandManager.literal("dismiss")
                        .then(CommandManager.argument("petName", StringArgumentType.greedyString())
                            .executes(context -> dismissPet(context))))
                    .then(CommandManager.literal("whistle+follow")
                        .executes(context -> callAndFollowPets(context))))
            );
        });
    }

    // Mount command implementations
    private static int callAllMounts(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return callMountsByName(context, "");
    }

    private static int callMountsByName(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String mountName = StringArgumentType.getString(context, "name");
        return callMountsByName(context, mountName);
    }

    private static int callPetsByName(CommandContext<ServerCommandSource> context, String petName) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        try {
            // Check cooldown first
            if (BeastMasterMod.isPlayerOnCooldown(player.getUuid())) {
                long remaining = BeastMasterMod.getCooldownRemaining(player.getUuid());
                long seconds = (remaining + 999) / 1000;
                player.sendMessage(Text.of("§cWhistle is on cooldown! " + seconds + " seconds remaining."), false);
                return 0;
            }

            // Set cooldown
            BeastMasterMod.setPlayerCooldown(player.getUuid());

            UUID playerUUID = player.getUuid();
            ServerWorld currentWorld = player.getWorld();
            MinecraftServer server = player.getServer();

            if (server == null) {
                player.sendMessage(Text.of("§cServer not available."), false);
                return 0;
            }

            List<PackManager.EntityData> ownedPets = BeastMasterMod.getAllRegisteredPets(server, playerUUID);

            if (ownedPets.isEmpty()) {
                player.sendMessage(Text.of("§cNo callable pets found!"), false);
                return 0;
            }

            // Filter pets by name if specified
            List<PackManager.EntityData> targetPets = new ArrayList<>();
            if (petName.isEmpty()) {
                targetPets.addAll(ownedPets);
                //player.sendMessage(Text.of("§7Attempting to call all " + targetPets.size() + " pets..."), false);
            } else {
                String searchName = petName.equalsIgnoreCase("Unknown") ? "Noname" : petName;
                for (PackManager.EntityData pet : ownedPets) {
                    String currentPetName = pet.customName != null ? pet.customName : "Noname";
                    if (currentPetName.equalsIgnoreCase(searchName)) {
                        targetPets.add(pet);
                    }
                }

                if (targetPets.isEmpty()) {
                    player.sendMessage(Text.of("§cNo pet found with name: " + petName), false);
                    player.sendMessage(Text.of("§7Use '/beast pet list' to see your callable pets."), false);
                    return 0;
                }
                player.sendMessage(Text.of("§7You have " + targetPets.size() + " callable pet" + (targetPets.size() > 1 ? "s" : "") + " named '" + petName + "', attempting to call..."), false);
            }

            List<Entity> summonablePets = new ArrayList<>();
            List<String> failedPets = new ArrayList<>();
            List<UUID> deadPets = new ArrayList<>();

            for (PackManager.EntityData petData : targetPets) {
                try {
                    // PET VERSION: ALWAYS use the NBT loading approach to ensure proper dimension handling
                    // This guarantees the entity is removed from its original dimension
                    Entity loadedPet = BeastMasterMod.loadAndTeleportEntity(server, petData, player);
                    if (loadedPet != null) {
                        summonablePets.add(loadedPet);
                    } else {
                        String currentPetName = petData.customName != null ? petData.customName : "Noname";
                        failedPets.add(currentPetName);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error processing pet {}", petData.entityUuid, e);
                    String currentPetName = petData.customName != null ? petData.customName : "Noname";
                    failedPets.add(currentPetName);
                }
            }

            // Remove dead pets from tracking
            removeDeadEntities(server, deadPets, player);

            if (summonablePets.isEmpty()) {
                if (!failedPets.isEmpty()) {
                    player.sendMessage(Text.of("§cFailed to call " + failedPets.size() + " pet" + (failedPets.size() > 1 ? "s" : "") + "."), false);
                }
                return 0;
            }

            // Teleport pets directly to player position
            int teleportedCount = teleportEntitiesToPlayer(summonablePets, player, failedPets);

            // Send result message
            //sendSummonResult(player, teleportedCount, failedPets, petName.isEmpty() ? "all pets" : "pet", petName.isEmpty() ? "all pets" : "'" + petName + "'");

            // Play custom whistle sound if enabled, otherwise use default sound
            if (BeastMasterMod.CONFIG != null && BeastMasterMod.CONFIG.enableWhistleSounds) {
                BeastMasterMod.playRandomWhistleSound(currentWorld, player);
            } else {
                currentWorld.playSound(null, player.getBlockPos(),
                    SoundEvents.ENTITY_WOLF_WHINE, player.getSoundCategory(), 1.0f, 1.0f);
            }

            return teleportedCount;

        } catch (Exception e) {
            LOGGER.error("Error in pet whistle command", e);
            player.sendMessage(Text.of("§cAn error occurred while executing the command."), false);
            return 0;
        }
    }

    // Pet command implementations
    private static int callAllPets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return callPetsByName(context, "");
    }

    private static int callPetsByName(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String petName = StringArgumentType.getString(context, "name");
        return callPetsByName(context, petName);
    }

    private static int callMountsByName(CommandContext<ServerCommandSource> context, String mountName) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        try {
            // Check cooldown first
            if (BeastMasterMod.isPlayerOnCooldown(player.getUuid())) {
                long remaining = BeastMasterMod.getCooldownRemaining(player.getUuid());
                long seconds = (remaining + 999) / 1000;
                player.sendMessage(Text.of("§cWhistle is on cooldown! " + seconds + " seconds remaining."), false);
                return 0;
            }

            // Set cooldown
            BeastMasterMod.setPlayerCooldown(player.getUuid());

            UUID playerUUID = player.getUuid();
            ServerWorld currentWorld = player.getWorld();
            MinecraftServer server = player.getServer();

            if (server == null) {
                player.sendMessage(Text.of("§cServer not available."), false);
                return 0;
            }

            List<PackManager.EntityData> ownedMounts = BeastMasterMod.getAllRegisteredMounts(server, playerUUID);

            if (ownedMounts.isEmpty()) {
                player.sendMessage(Text.of("§cNo callable mounts found!"), false);
                return 0;
            }

            // Filter mounts by name if specified
            List<PackManager.EntityData> targetMounts = new ArrayList<>();
            if (mountName.isEmpty()) {
                targetMounts.addAll(ownedMounts);
                player.sendMessage(Text.of("§7Attempting to call all " + targetMounts.size() + " mounts..."), false);
            } else {
                String searchName = mountName.equalsIgnoreCase("Unknown") ? "Noname" : mountName;
                for (PackManager.EntityData mount : ownedMounts) {
                    String currentMountName = mount.customName != null ? mount.customName : "Noname";
                    if (currentMountName.equalsIgnoreCase(searchName)) {
                        targetMounts.add(mount);
                    }
                }

                if (targetMounts.isEmpty()) {
                    player.sendMessage(Text.of("§cNo mount found with name: " + mountName), false);
                    player.sendMessage(Text.of("§7Use '/beast mount list' to see your callable mounts."), false);
                    return 0;
                }
                player.sendMessage(Text.of("§7You have " + targetMounts.size() + " callable mount" + (targetMounts.size() > 1 ? "s" : "") + " named '" + mountName + "', attempting to call..."), false);
            }

            List<Entity> summonableMounts = new ArrayList<>();
            List<String> failedMounts = new ArrayList<>();
            List<UUID> deadMounts = new ArrayList<>();

            for (PackManager.EntityData mountData : targetMounts) {
                try {
                    // ALWAYS use the NBT loading approach to ensure proper dimension handling
                    // This guarantees the entity is removed from its original dimension
                    Entity loadedMount = BeastMasterMod.loadAndTeleportEntity(server, mountData, player);
                    if (loadedMount != null) {
                        summonableMounts.add(loadedMount);
                    } else {
                        String currentMountName = mountData.customName != null ? mountData.customName : "Noname";
                        failedMounts.add(currentMountName);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error processing mount {}", mountData.entityUuid, e);
                    String currentMountName = mountData.customName != null ? mountData.customName : "Noname";
                    failedMounts.add(currentMountName);
                }
            }

            // Remove dead mounts from tracking
            removeDeadEntities(server, deadMounts, player);

            if (summonableMounts.isEmpty()) {
                if (!failedMounts.isEmpty()) {
                    player.sendMessage(Text.of("§cFailed to call " + failedMounts.size() + " mount" + (failedMounts.size() > 1 ? "s" : "") + "."), false);
                }
                return 0;
            }

            // Teleport mounts directly to player position
            int teleportedCount = teleportEntitiesToPlayer(summonableMounts, player, failedMounts);

            // Send result message
            //sendSummonResult(player, teleportedCount, failedMounts, mountName.isEmpty() ? "all mounts" : "mount", mountName.isEmpty() ? "all mounts" : "'" + mountName + "'");

            // Play custom whistle sound if enabled, otherwise use default sound
            if (BeastMasterMod.CONFIG != null && BeastMasterMod.CONFIG.enableWhistleSounds) {
                BeastMasterMod.playRandomWhistleSound(currentWorld, player);
            } else {
                currentWorld.playSound(null, player.getBlockPos(),
                    SoundEvents.ENTITY_HORSE_GALLOP, player.getSoundCategory(), 1.0f, 1.0f);
            }

            return teleportedCount;

        } catch (Exception e) {
            LOGGER.error("Error in mount whistle command", e);
            player.sendMessage(Text.of("§cAn error occurred while executing the command."), false);
            return 0;
        }
    }

    private static int findMounts(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        int found = comprehensiveEntitySearch(player.getServer(), player.getUuid(), false);

        // Send feedback to player
        if (found > 0) {
            player.sendMessage(Text.of("§aFound and registered " + found + " mount" + (found > 1 ? "s" : "") + "!"), false);
        } else {
            player.sendMessage(Text.of("§cNo mounts found to register. Make sure you have tamed mounts nearby."), false);
        }
        return found;
    }

    private static int findPets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        int found = comprehensiveEntitySearch(player.getServer(), player.getUuid(), true);

        // Send feedback to player
        if (found > 0) {
            player.sendMessage(Text.of("§aFound and registered " + found + " pet" + (found > 1 ? "s" : "") + "!"), false);
        } else {
            player.sendMessage(Text.of("§cNo pets found to register. Make sure you have tamed pets nearby."), false);
        }
        return found;
    }

    private static int listMounts(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        return listEntities(player, false, "mounts");
    }

    private static int listPets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        return listEntities(player, true, "pets");
    }

    private static int debugMounts(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        return debugEntities(player, false, "Mounts");
    }

    private static int debugPets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        return debugEntities(player, true, "Pets");
    }

    private static int comprehensiveEntitySearch(MinecraftServer server, UUID playerUUID, boolean searchPets) {
        int registered = 0;
        try {
            LOGGER.debug("Starting entity search for player: {}, type: {}", playerUUID, searchPets ? "PETS" : "MOUNTS");

            // Find the player to search around them
            ServerPlayerEntity player = null;
            for (ServerWorld world : server.getWorlds()) {
                PlayerEntity foundPlayer = world.getPlayerByUuid(playerUUID);
                if (foundPlayer instanceof ServerPlayerEntity) {
                    player = (ServerPlayerEntity) foundPlayer;
                    break;
                }
            }
            
            if (player == null) {
                LOGGER.warn("Player not found for entity search: {}", playerUUID);
                return 0;
            }

            // Search only in the player's current world and around them
            ServerWorld playerWorld = (ServerWorld) player.getWorld();
            PackManager manager = PackManager.get(playerWorld);
            
            // Search in a reasonable radius around the player (64 blocks)
            List<Entity> entitiesInWorld = playerWorld.getEntitiesByClass(
                Entity.class,
                player.getBoundingBox().expand(64.0),
                entity -> {
                    boolean isCorrectType = searchPets ?
                        BeastConfig.isSupportedPet(entity) :
                        BeastConfig.isSupportedMount(entity);
                    boolean isOwned = BeastMasterMod.isOwnedByPlayer(entity, playerUUID);
                    boolean isAlive = entity.isAlive();
                    
                    return isCorrectType && isOwned && isAlive;
                }
            );

            LOGGER.debug("Found {} matching entities near player", entitiesInWorld.size());

            for (Entity entity : entitiesInWorld) {
                manager.storeEntityNbt(entity);
                registered++;
                LOGGER.debug("Registered: {} ({})", entity.getUuid(), entity.getType().getTranslationKey());
            }

        } catch (Exception e) {
            LOGGER.error("Error in comprehensiveEntitySearch", e);
        }
        return registered;
    }

    private static int listEntities(ServerPlayerEntity player, boolean listPets, String typeName) {
        try {
            List<PackManager.EntityData> ownedEntities = listPets ?
                BeastMasterMod.getAllRegisteredPets(player.getServer(), player.getUuid()) :
                BeastMasterMod.getAllRegisteredMounts(player.getServer(), player.getUuid());

            // DEDUPLICATE: Only show the most recent entry for each UUID
            Map<UUID, PackManager.EntityData> mostRecentEntities = new HashMap<>();
            for (PackManager.EntityData entityData : ownedEntities) {
                // FILTER OUT DEAD ENTITIES
                if (BeastMasterMod.isEntityDeadGlobally(entityData.entityUuid)) {
                    continue;
                }
                PackManager.EntityData existing = mostRecentEntities.get(entityData.entityUuid);
                if (existing == null || entityData.timestamp > existing.timestamp) {
                    mostRecentEntities.put(entityData.entityUuid, entityData);
                }
            }

            List<PackManager.EntityData> deduplicatedEntities = new ArrayList<>(mostRecentEntities.values());

            player.sendMessage(Text.of("§7You have " + deduplicatedEntities.size() + " callable " + typeName + "."), false);

            if (deduplicatedEntities.isEmpty()) {
                player.sendMessage(Text.of("§7- None"), false);
            } else {
                // Sort entities alphabetically by name
                deduplicatedEntities.sort((e1, e2) -> {
                    String name1 = e1.customName != null ? e1.customName : "Noname";
                    String name2 = e2.customName != null ? e2.customName : "Noname";
                    return name1.compareToIgnoreCase(name2);
                });

                for (PackManager.EntityData entityData : deduplicatedEntities) {
                    String entityName = entityData.customName != null ? entityData.customName : "Noname";
                    String location = String.format("(%.0f, %.0f, %.0f)",
                        entityData.x, entityData.y, entityData.z);
                    player.sendMessage(Text.of("§7- " + entityName + " " + location), false);
                }
            }

            String findCommand = listPets ? "/beast pet find" : "/beast mount find";
            String whistleCommand = listPets ? "/beast pet whistle" : "/beast mount whistle";
            player.sendMessage(Text.of("§7Use '" + findCommand + "' to make nearby " + typeName + " callable, and '" + whistleCommand + " <name>' to call them!"), false);

            return 1;
        } catch (Exception e) {
            LOGGER.error("Error in list command", e);
            player.sendMessage(Text.of("§cAn error occurred."), false);
            return 0;
        }
    }

    private static int debugEntities(ServerPlayerEntity player, boolean debugPets, String debugType) {
        try {
            MinecraftServer server = player.getServer();
            UUID playerUUID = player.getUuid();

            player.sendMessage(Text.of("§6=== Beast Master " + debugType + " Debug Info ==="), false);
            player.sendMessage(Text.of("§6Player UUID: " + playerUUID), false);

            // Count unique loaded entities (no duplicates)
            Set<UUID> loadedEntityUUIDs = new HashSet<>();
            for (ServerWorld world : server.getWorlds()) {
                List<Entity> entities = world.getEntitiesByClass(Entity.class,
                    new Box(-30000000, -256, -30000000, 30000000, 256, 30000000),
                    entity -> {
                        boolean isCorrectType = debugPets ?
                            BeastConfig.isSupportedPet(entity) :
                            BeastConfig.isSupportedMount(entity);
                        return isCorrectType && BeastMasterMod.isOwnedByPlayer(entity, playerUUID);
                    });
                for (Entity entity : entities) {
                    loadedEntityUUIDs.add(entity.getUuid());
                }
            }
            player.sendMessage(Text.of("§6Currently loaded " + debugType.toLowerCase() + ": " + loadedEntityUUIDs.size()), false);

            // Count unique registered entities (SIMPLIFIED - no per-dimension breakdown)
            List<PackManager.EntityData> registeredEntities = debugPets ?
                BeastMasterMod.getAllRegisteredPets(server, playerUUID) :
                BeastMasterMod.getAllRegisteredMounts(server, playerUUID);

            // Deduplicate registered entities by UUID
            Set<UUID> uniqueRegisteredUUIDs = new HashSet<>();
            int aliveCount = 0;
            int deadCount = 0;

            for (PackManager.EntityData entityData : registeredEntities) {
                uniqueRegisteredUUIDs.add(entityData.entityUuid);
                if (entityData.isAlive) {
                    aliveCount++;
                } else {
                    deadCount++;
                }
            }

            player.sendMessage(Text.of("§6Total tracked " + debugType.toLowerCase() + ": " + uniqueRegisteredUUIDs.size()), false);
            player.sendMessage(Text.of("§a- Alive: " + aliveCount), false);
            player.sendMessage(Text.of("§c- Dead: " + deadCount + " (will be cleaned up)"), false);

            return 1;
        } catch (Exception e) {
            LOGGER.error("Error in debug command", e);
            player.sendMessage(Text.of("§cAn error occurred."), false);
            return 0;
        }
    }

    private static int teleportEntitiesToPlayer(List<Entity> entities, ServerPlayerEntity player, List<String> failedList) {
        int teleportedCount = 0;
        for (Entity entity : entities) {
            try {
                // Teleport directly to player position
                entity.teleport(player.getX(), player.getY(), player.getZ());

                // Stop navigation for certain entities
                if (entity instanceof HorseEntity horse) {
                    horse.getNavigation().stop();
                }

                teleportedCount++;
            } catch (Exception e) {
                LOGGER.error("Error teleporting entity {}", entity.getUuid(), e);
                String entityName = entity.hasCustomName() ? entity.getCustomName().getString() : "Noname";
                failedList.add(entityName);
            }
        }
        return teleportedCount;
    }

    private static void removeDeadEntities(MinecraftServer server, List<UUID> deadEntities, ServerPlayerEntity player) {
        if (!deadEntities.isEmpty()) {
            for (UUID deadEntityId : deadEntities) {
                for (ServerWorld world : server.getWorlds()) {
                    PackManager manager = PackManager.get(world);
                    if (manager.isEntityTracked(deadEntityId)) {
                        manager.untrackEntity(deadEntityId);
                    }
                }
            }
            player.sendMessage(Text.of("§6Removed " + deadEntities.size() + " dead entit" + (deadEntities.size() > 1 ? "ies" : "y") + " from callable list."), false);
        }
    }

    private static void sendSummonResult(ServerPlayerEntity player, int count, List<String> failed, String type, String name) {

        StringBuilder message = new StringBuilder();
        message.append("§aCalled ").append(count).append(" ").append(name).append("!");

        if (!failed.isEmpty()) {
            message.append("\n§c(").append(failed.size()).append(" failed to call.)");
        }

        if (message.length() > 0) {
            player.sendMessage(Text.of(message.toString()), false);
        }
    }

    @Override
    public int run(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return callAllMounts(context);
    }

    // Set free methods
    private static int setFreePet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String petName = StringArgumentType.getString(context, "petName");
        return setFreeEntity(context, true, petName);
    }

    private static int setFreeMount(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String mountName = StringArgumentType.getString(context, "mountName");
        return setFreeEntity(context, false, mountName);
    }

    private static int setFreeEntity(CommandContext<ServerCommandSource> context, boolean isPet, String entityName) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        try {
            // Find the target entity
            Entity targetEntity = findTargetEntityByName(player, isPet, entityName);
            if (targetEntity == null) {
                String type = isPet ? "pet" : "mount";
                player.sendMessage(Text.of("§cNo owned " + type + " found with name: " + entityName), false);
                player.sendMessage(Text.of("§7Use '/beast " + type + " list' to see your callable " + type + "s."), false);
                return 0;
            }

            String originalName = getEntityName(targetEntity);
            String entityType = targetEntity.getType().getTranslationKey();
            boolean success;

            if (isPet) {
                // For pets - just set free normally
                success = setPetFree(targetEntity, player);
                if (success) {
                    player.sendMessage(Text.of("§a" + originalName + " has been set free and is now wild!"), false);
                    player.sendMessage(Text.of("§7The emotional damage may cause it to appear confused temporarily."), false);
                    player.sendMessage(Text.of("§7Relog or come back later to see it wandering freely."), false);
                }
            } else {
                // For mounts - check if vanilla or modded
                boolean isVanilla = isVanillaMount(targetEntity);

                if (isVanilla) {
                    success = setVanillaMountFree(targetEntity);
                    if (success) {
                        player.sendMessage(Text.of("§a" + originalName + " has been set free and is now wild!"), false);
                    }
                } else {
                    success = setModdedMountFree(targetEntity);
                    if (success) {
                        player.sendMessage(Text.of("§a" + originalName + " has been released from ownership."), false);
                    }
                }
            }

            if (success) {
                // Remove from tracking
                PackManager manager = PackManager.get((ServerWorld) player.getWorld());
                manager.untrackEntity(targetEntity.getUuid());
                return 1;
            } else {
                player.sendMessage(Text.of("§cFailed to set " + originalName + " free."), false);
                return 0;
            }

        } catch (Exception e) {
            LOGGER.error("Error setting entity free", e);
            player.sendMessage(Text.of("§cAn error occurred while setting the entity free."), false);
            return 0;
        }
    }

    // Dismiss methods
    private static int dismissPet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String petName = StringArgumentType.getString(context, "petName");
        return dismissEntity(context, true, petName);
    }

    private static int dismissMount(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String mountName = StringArgumentType.getString(context, "mountName");
        return dismissEntity(context, false, mountName);
    }

    private static int dismissEntity(CommandContext<ServerCommandSource> context, boolean isPet, String entityName) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        try {
            // Find the target entity
            Entity targetEntity = findTargetEntityByName(player, isPet, entityName);
            if (targetEntity == null) {
                String type = isPet ? "pet" : "mount";
                player.sendMessage(Text.of("§cNo owned " + type + " found with name: " + entityName), false);
                player.sendMessage(Text.of("§7Use '/beast " + type + " list' to see your callable " + type + "s."), false);
                return 0;
            }

            String entityNameStr = getEntityName(targetEntity);

            // DROP INVENTORY BEFORE REMOVAL (NEW)
            int droppedItems = 0;
            if (!isPet) {
                // For mounts, drop all inventory first
                droppedItems = dropAllInventory(targetEntity);
            }

            // Remove entity from game
            targetEntity.remove(Entity.RemovalReason.DISCARDED);
            player.sendMessage(Text.of("§aYou dismissed " + entityNameStr + " from the world."), false);

            // Remove from tracking
            PackManager manager = PackManager.get((ServerWorld) player.getWorld());
            manager.untrackEntity(targetEntity.getUuid());

            return 1;

        } catch (Exception e) {
            LOGGER.error("Error dismissing entity", e);
            player.sendMessage(Text.of("§cAn error occurred while dismissing the entity."), false);
            return 0;
        }
    }

    // Core implementation methods
    public static boolean setPetFree(Entity entity, ServerPlayerEntity player) {
        if (entity instanceof TameableEntity tameable && tameable.isTamed()) {
            LOGGER.info("Setting pet free: {}", entity.getUuid());

            // Clear owner UUID
            tameable.setOwnerUuid(null);

            // Use NBT to clear tamed and sitting states
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            nbt.putBoolean("Tame", false);
            nbt.putBoolean("Sitting", false);
            nbt.remove("Owner");
            entity.readNbt(nbt);

            // Entity-specific behavior reset
            if (entity instanceof WolfEntity wolf) {
                wolf.setAngryAt(null);
                wolf.setTarget(null);
                wolf.setSitting(false);
            }

            // Clear custom name
            entity.setCustomName(null);

            // EMOTIONAL DAMAGE! 💔 (forces sync attempt)
            try {
                entity.damage(net.minecraft.entity.damage.DamageSource.GENERIC, 0.0f);
            } catch (Exception e) {
                LOGGER.warn("Emotional damage failed: {}", e.getMessage());
            }

            LOGGER.info("Successfully set pet free with emotional damage: {}", entity);
            return true;
        }
        LOGGER.debug("Entity {} is not a tamed pet", entity);
        return false;
    }

    private static boolean isVanillaMount(Entity entity) {
        return entity instanceof HorseEntity ||
               entity instanceof DonkeyEntity ||
               entity instanceof MuleEntity ||
               entity instanceof LlamaEntity ||
               entity instanceof PigEntity;
    }

    private static boolean setVanillaMountFree(Entity original) {
        try {
            LOGGER.info("Setting vanilla mount free via NBT: {}", original.getUuid());

            NbtCompound nbt = new NbtCompound();
            original.writeNbt(nbt);

            // Remove ownership tags
            nbt.remove("Owner");
            nbt.remove("OwnerUUID");
            nbt.putBoolean("Tame", false);

            original.readNbt(nbt);

            // Clear custom name
            original.setCustomName(null);

            LOGGER.info("Successfully set vanilla mount free: {}", original.getUuid());
            return true;
        } catch (Exception e) {
            LOGGER.error("Error setting vanilla mount free", e);
            return false;
        }
    }

    private static boolean setModdedMountFree(Entity original) {
        try {
            LOGGER.info("Setting modded mount free: {} ({})",
                original.getUuid(), original.getType().getTranslationKey());

            // For modded mounts, we can't reliably drop inventory
            // Just remove the entity - at least it's gone from the world
            original.remove(Entity.RemovalReason.DISCARDED);

            return true;
        } catch (Exception e) {
            LOGGER.error("Error setting modded mount free", e);
            return false;
        }
    }

    private static int dropAllInventory(Entity entity) {
        int droppedCount = 0;

        try {
            if (entity instanceof HorseEntity horse) {
                // Drop generic saddle
                if (horse.isSaddled()) {
                    horse.getWorld().spawnEntity(new ItemEntity(horse.getWorld(),
                        horse.getX(), horse.getY(), horse.getZ(), new ItemStack(Items.SADDLE)));
                    horse.saddle(null); // Remove saddle from horse
                    droppedCount++;
                }

                // Drop EXACT horse armor (preserves enchantments)
                NbtCompound horseNbt = new NbtCompound();
                horse.writeNbt(horseNbt);
                if (horseNbt.contains("ArmorItem") && !horseNbt.getCompound("ArmorItem").isEmpty()) {
                    NbtCompound armorNbt = horseNbt.getCompound("ArmorItem");
                    ItemStack armorStack = ItemStack.fromNbt(armorNbt);
                    if (!armorStack.isEmpty()) {
                        horse.getWorld().spawnEntity(new ItemEntity(horse.getWorld(),
                            horse.getX(), horse.getY(), horse.getZ(), armorStack));
                        horseNbt.remove("ArmorItem"); // REMOVE from horse
                        horse.readNbt(horseNbt);
                        droppedCount++;
                    }
                }
            }

            else if (entity instanceof DonkeyEntity donkey) {
                // Drop generic saddle
                NbtCompound donkeyNbt = new NbtCompound();
                donkey.writeNbt(donkeyNbt);
                if (donkeyNbt.contains("SaddleItem") && !donkeyNbt.getCompound("SaddleItem").isEmpty()) {
                    donkey.getWorld().spawnEntity(new ItemEntity(donkey.getWorld(),
                        donkey.getX(), donkey.getY(), donkey.getZ(), new ItemStack(Items.SADDLE)));
                    donkeyNbt.remove("SaddleItem"); // REMOVE from donkey
                    donkey.readNbt(donkeyNbt);
                    droppedCount++;
                }

                // Drop chest contents
                droppedCount += dropAndClearDonkeyChestContents(donkey);
            }

            else if (entity instanceof MuleEntity mule) {
                // Drop generic saddle
                NbtCompound muleNbt = new NbtCompound();
                mule.writeNbt(muleNbt);
                if (muleNbt.contains("SaddleItem") && !muleNbt.getCompound("SaddleItem").isEmpty()) {
                    mule.getWorld().spawnEntity(new ItemEntity(mule.getWorld(),
                        mule.getX(), mule.getY(), mule.getZ(), new ItemStack(Items.SADDLE)));
                    muleNbt.remove("SaddleItem"); // REMOVE from mule
                    mule.readNbt(muleNbt);
                    droppedCount++;
                }

                // Drop chest contents
                droppedCount += dropAndClearDonkeyChestContents(mule);
            }

            else if (entity instanceof LlamaEntity llama) {
                // Drop decorations (carpets)
                droppedCount += dropAndClearLlamaChestContents(llama);
            }

            else if (entity instanceof PigEntity pig) {
                // Drop generic saddle
                if (pig.isSaddled()) {
                    pig.getWorld().spawnEntity(new ItemEntity(pig.getWorld(),
                        pig.getX(), pig.getY(), pig.getZ(), new ItemStack(Items.SADDLE)));
                    pig.saddle(null);
                    droppedCount++;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error dropping inventory", e);
        }

        return droppedCount;
    }

    private static int dropAndClearDonkeyChestContents(AbstractDonkeyEntity donkey) {
        int droppedCount = 0;
        try {
            NbtCompound donkeyNbt = new NbtCompound();
            donkey.writeNbt(donkeyNbt);

            // Check if donkey has chest
            if (donkeyNbt.getBoolean("ChestedHorse")) {
                // Drop chest items and CLEAR them
                if (donkeyNbt.contains("Items")) {
                    NbtList items = donkeyNbt.getList("Items", 10);
                    for (int i = 0; i < items.size(); i++) {
                        NbtCompound itemNbt = items.getCompound(i);
                        ItemStack stack = ItemStack.fromNbt(itemNbt);
                        if (!stack.isEmpty()) {
                            donkey.getWorld().spawnEntity(new ItemEntity(donkey.getWorld(),
                                donkey.getX(), donkey.getY(), donkey.getZ(), stack));
                            droppedCount++;
                        }
                    }
                    // CLEAR all items from donkey
                    donkeyNbt.remove("Items");
                }

                // Remove chest from donkey
                donkeyNbt.putBoolean("ChestedHorse", false);
                donkey.readNbt(donkeyNbt);
            }
        } catch (Exception e) {
            LOGGER.error("Error dropping donkey chest contents", e);
        }
        return droppedCount;
    }

    private static int dropAndClearLlamaChestContents(LlamaEntity llama) {
        int droppedCount = 0;
        try {
            NbtCompound llamaNbt = new NbtCompound();
            llama.writeNbt(llamaNbt);

            // Drop decorations (carpets)
            if (llamaNbt.contains("DecorItem") && !llamaNbt.getCompound("DecorItem").isEmpty()) {
                NbtCompound decorNbt = llamaNbt.getCompound("DecorItem");
                ItemStack decorStack = ItemStack.fromNbt(decorNbt);
                if (!decorStack.isEmpty()) {
                    llama.getWorld().spawnEntity(new ItemEntity(llama.getWorld(),
                        llama.getX(), llama.getY(), llama.getZ(), decorStack));
                    llamaNbt.remove("DecorItem"); // REMOVE from llama
                    droppedCount++;
                }
            }

            // Drop chest contents
            if (llamaNbt.contains("Items")) {
                NbtList items = llamaNbt.getList("Items", 10);
                for (int i = 0; i < items.size(); i++) {
                    NbtCompound itemNbt = items.getCompound(i);
                    ItemStack stack = ItemStack.fromNbt(itemNbt);
                    if (!stack.isEmpty()) {
                        llama.getWorld().spawnEntity(new ItemEntity(llama.getWorld(),
                            llama.getX(), llama.getY(), llama.getZ(), stack));
                        droppedCount++;
                    }
                }
                // CLEAR all items from llama
                llamaNbt.remove("Items");
            }

            // Apply changes
            if (droppedCount > 0) {
                llama.readNbt(llamaNbt);
            }
        } catch (Exception e) {
            LOGGER.error("Error dropping llama chest contents", e);
        }
        return droppedCount;
    }

    // This is only used on setfree and dismiss (so you never release a pet you aren't looking at)
    private static Entity findTargetEntityByName(ServerPlayerEntity player, boolean findPet, String entityName) {
        List<Entity> entities = player.getWorld().getOtherEntities(player,
            player.getBoundingBox().expand(50.0), // Large radius to find named entities
            entity -> {
                boolean correctType = findPet ?
                    BeastConfig.isSupportedPet(entity) :
                    BeastConfig.isSupportedMount(entity);
                boolean isOwned = BeastMasterMod.isOwnedByPlayer(entity, player.getUuid());

                // FIX: Special handling for "Noname" to match unnamed entities
                boolean nameMatches;
                if (entityName.equalsIgnoreCase("Noname")) {
                    nameMatches = !entity.hasCustomName(); // Match entities with no custom name
                } else {
                    nameMatches = entity.hasCustomName() &&
                        entity.getCustomName().getString().equalsIgnoreCase(entityName);
                }

                return correctType && isOwned && entity.isAlive() && nameMatches;
            });

        if (entities.isEmpty()) {
            return null;
        }

        // NEW: Find the closest entity instead of just the first one
        Entity closestEntity = null;
        double closestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            double distance = entity.squaredDistanceTo(player);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        return closestEntity;
    }

    private static String getEntityName(Entity entity) {
        if (entity.hasCustomName() && entity.getCustomName() != null) {
            return entity.getCustomName().getString();
        } else {
            return "Your " + entity.getType().getTranslationKey().replace("entity.minecraft.", "");
        }
    }

    private static int executePetSit(ServerCommandSource source) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (CommandSyntaxException e) {
            source.sendError(Text.of("This command can only be used by a player."));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        MinecraftServer server = player.getServer();
        if (server == null) return 0;

        int petsSitted = 0;
        List<String> failedPets = new ArrayList<>();
        Set<UUID> processedPets = new HashSet<>(); // NEW: Track processed pets to avoid duplicates

        // Use cached registry from PackManager
        List<PackManager.EntityData> playerPets = BeastMasterMod.getAllRegisteredPets(server, playerUUID);

        for (PackManager.EntityData petData : playerPets) {
            // NEW: Skip if we've already processed this pet (avoid duplicates)
            if (processedPets.contains(petData.entityUuid)) {
                continue;
            }

            try {
                Entity entity = BeastMasterMod.findEntityInAnyWorld(server, petData.entityUuid);

                if (entity instanceof Tameable tameable && entity.isAlive()) {
                    // NEW: Mark this pet as processed
                    processedPets.add(petData.entityUuid);

                    // For Tameable entities in 1.18.2, we need to use NBT to set sitting
                    NbtCompound nbt = new NbtCompound();
                    entity.writeNbt(nbt);
                    nbt.putBoolean("Sitting", true);
                    entity.readNbt(nbt);

                    petsSitted++;

                    // Special handling for different pet types
                    if (entity instanceof WolfEntity wolf) {
                        wolf.setAngryAt(null);
                        wolf.setTarget(null);
                        wolf.setInSittingPose(true);
                    } else if (entity instanceof CatEntity cat) {
                        cat.setInSittingPose(true);
                    } else if (entity instanceof ParrotEntity parrot) {
                        parrot.setInSittingPose(true);
                    }

                    // Update the entity in PackManager
                    if (entity.getWorld() instanceof ServerWorld serverWorld) {
                        PackManager manager = PackManager.get(serverWorld);
                        manager.storeEntityNbt(entity);
                    }

                } else {
                    // Entity not found or not alive
                    String petName = petData.customName != null ? petData.customName : "Unnamed";
                    failedPets.add(petName);
                }
            } catch (Exception e) {
                BeastMasterMod.LOGGER.error("Error making pet sit: {}", petData.entityUuid, e);
                String petName = petData.customName != null ? petData.customName : "Unnamed";
                failedPets.add(petName);
            }
        }

        // Send result message
        if (petsSitted > 0) {
            //source.sendFeedback(Text.of("§a" + petsSitted + " pets are now sitting."), false);
        }
        if (!failedPets.isEmpty()) {
            //source.sendFeedback(Text.of("§c" + failedPets.size() + " pets could not be found."), false);
        }
        if (petsSitted == 0 && failedPets.isEmpty()) {
            source.sendFeedback(Text.of("§eNo pets found to sit."), false);
        }

        return petsSitted;
    }

    private static int executePetFollow(ServerCommandSource source, int maxCount) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (CommandSyntaxException e) {
            source.sendError(Text.of("This command can only be used by a player."));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        MinecraftServer server = player.getServer();
        if (server == null) return 0;

        int petsFollowing = 0;
        List<String> failedPets = new ArrayList<>();
        Set<UUID> processedPets = new HashSet<>(); // NEW: Track processed pets to avoid duplicates

        // Use cached registry from PackManager
        List<PackManager.EntityData> playerPets = BeastMasterMod.getAllRegisteredPets(server, playerUUID);

        // If we have a count limit, we need to sort pets by distance
        if (maxCount > 0) {
            // Create a list of pets with their distances
            List<PetWithDistance> petsWithDistance = new ArrayList<>();

            for (PackManager.EntityData petData : playerPets) {
                // NEW: Skip if already processed
                if (processedPets.contains(petData.entityUuid)) {
                    continue;
                }

                try {
                    Entity entity = BeastMasterMod.findEntityInAnyWorld(server, petData.entityUuid);
                    if (entity instanceof Tameable && entity.isAlive()) {
                        double distance = entity.squaredDistanceTo(player);
                        petsWithDistance.add(new PetWithDistance(petData, entity, distance));
                        processedPets.add(petData.entityUuid); // NEW: Mark as processed
                    }
                } catch (Exception e) {
                    // Skip pets we can't process
                }
            }

            // Sort by distance (closest first)
            petsWithDistance.sort(Comparator.comparingDouble(p -> p.distance));

            // Process only the closest N pets
            int processed = 0;
            for (PetWithDistance petWithDistance : petsWithDistance) {
                if (processed >= maxCount) break;

                try {
                    Entity entity = petWithDistance.entity;
                    if (entity instanceof Tameable tameable && entity.isAlive()) {
                        // For Tameable entities in 1.18.2, we need to use NBT to stop sitting
                        NbtCompound nbt = new NbtCompound();
                        entity.writeNbt(nbt);
                        nbt.putBoolean("Sitting", false);
                        entity.readNbt(nbt);

                        petsFollowing++;

                        // Clear any aggression and stop sitting pose
                        if (entity instanceof WolfEntity wolf) {
                            wolf.setAngryAt(null);
                            wolf.setTarget(null);
                            wolf.setInSittingPose(false);
                        } else if (entity instanceof CatEntity cat) {
                            cat.setInSittingPose(false);
                        } else if (entity instanceof ParrotEntity parrot) {
                            parrot.setInSittingPose(false);
                        }

                        // Teleport pet to player if it's too far away
                        if (entity.squaredDistanceTo(player) > 10000) { // 100 blocks squared
                            entity.teleport(player.getX(), player.getY(), player.getZ());
                        }

                        // Update the entity in PackManager
                        if (entity.getWorld() instanceof ServerWorld serverWorld) {
                            PackManager manager = PackManager.get(serverWorld);
                            manager.storeEntityNbt(entity);
                        }

                    }
                } catch (Exception e) {
                    BeastMasterMod.LOGGER.error("Error making pet follow: {}", petWithDistance.petData.entityUuid, e);
                    String petName = petWithDistance.petData.customName != null ? petWithDistance.petData.customName : "Unnamed";
                    failedPets.add(petName);
                }
                processed++;
            }
        } else {
            // No count limit - process all pets (original behavior)
            for (PackManager.EntityData petData : playerPets) {
                // NEW: Skip if already processed
                if (processedPets.contains(petData.entityUuid)) {
                    continue;
                }

                try {
                    Entity entity = BeastMasterMod.findEntityInAnyWorld(server, petData.entityUuid);

                    if (entity instanceof Tameable tameable && entity.isAlive()) {
                        // NEW: Mark as processed
                        processedPets.add(petData.entityUuid);

                        // For Tameable entities in 1.18.2, we need to use NBT to stop sitting
                        NbtCompound nbt = new NbtCompound();
                        entity.writeNbt(nbt);
                        nbt.putBoolean("Sitting", false);
                        entity.readNbt(nbt);

                        petsFollowing++;

                        // Clear any aggression and stop sitting pose
                        if (entity instanceof WolfEntity wolf) {
                            wolf.setAngryAt(null);
                            wolf.setTarget(null);
                            wolf.setInSittingPose(false);
                        } else if (entity instanceof CatEntity cat) {
                            cat.setInSittingPose(false);
                        } else if (entity instanceof ParrotEntity parrot) {
                            parrot.setInSittingPose(false);
                        }

                        // Teleport pet to player if it's too far away
                        if (entity.squaredDistanceTo(player) > 10000) { // 100 blocks squared
                            entity.teleport(player.getX(), player.getY(), player.getZ());
                        }

                        // Update the entity in PackManager
                        if (entity.getWorld() instanceof ServerWorld serverWorld) {
                            PackManager manager = PackManager.get(serverWorld);
                            manager.storeEntityNbt(entity);
                        }

                    } else {
                        // Entity not found or not alive
                        String petName = petData.customName != null ? petData.customName : "Unnamed";
                        failedPets.add(petName);
                    }
                } catch (Exception e) {
                    BeastMasterMod.LOGGER.error("Error making pet follow: {}", petData.entityUuid, e);
                    String petName = petData.customName != null ? petData.customName : "Unnamed";
                    failedPets.add(petName);
                }
            }
        }

        // Send result message
        if (petsFollowing > 0) {
            if (maxCount > 0) {
                //source.sendFeedback(Text.of("§a" + petsFollowing + " closest pets are now following you."), false);
            } else {
                //source.sendFeedback(Text.of("§a" + petsFollowing + " pets are now following you."), false);
            }
        }
        if (!failedPets.isEmpty()) {
            source.sendFeedback(Text.of("§c" + failedPets.size() + " pets could not be found."), false);
        }
        if (petsFollowing == 0 && failedPets.isEmpty()) {
            source.sendFeedback(Text.of("§eNo pets found to follow"), false);
        }

        return petsFollowing;
    }

    // Helper class for sorting pets by distance
    private static class PetWithDistance {
        final PackManager.EntityData petData;
        final Entity entity;
        final double distance;

        PetWithDistance(PackManager.EntityData petData, Entity entity, double distance) {
            this.petData = petData;
            this.entity = entity;
            this.distance = distance;
        }
    }


    private static int callAndFollowPets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        try {
            // First call all pets
            int summoned = callPetsByName(context, "");

            // Then make them follow
            if (summoned > 0) {
                executePetFollow(source, -1); // Make all pets follow
                //player.sendMessage(Text.of("§aYour pets are following you!"), false);
            }

            return summoned;
        } catch (Exception e) {
            LOGGER.error("Error in whistle+follow command", e);
            player.sendMessage(Text.of("§cAn error occurred."), false);
            return 0;
        }
    }


}
