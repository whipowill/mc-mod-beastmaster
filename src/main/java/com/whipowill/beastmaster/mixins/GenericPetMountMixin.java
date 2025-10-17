package com.whipowill.beastmaster.mixins;

import com.whipowill.beastmaster.BeastMasterMod;
import com.whipowill.beastmaster.BeastConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(LivingEntity.class)
public abstract class GenericPetMountMixin {

    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void onDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;
        Entity entity = (Entity)(Object)this;

        // Only process if it's one of our supported entities
        if (!BeastConfig.isSupportedPet(entity) && !BeastConfig.isSupportedMount(entity)) {
            return;
        }

        if (BeastMasterMod.isOwned(entity)) {
            // Check if friendly fire is disabled and damage is from owner OR friendly pet
            if (BeastMasterMod.CONFIG != null &&
                BeastMasterMod.CONFIG.disableFriendlyFire &&
                isDamageFromFriend(source, entity)) {

                cir.setReturnValue(false); // Cancel the damage entirely
                return;
            }

            boolean isPet = BeastMasterMod.CONFIG != null &&
                           BeastMasterMod.CONFIG.petImmortal &&
                           BeastConfig.isSupportedPet(entity);

            boolean isMount = BeastMasterMod.CONFIG != null &&
                             BeastMasterMod.CONFIG.mountImmortal &&
                             BeastConfig.isSupportedMount(entity);

            // For aggressive pets, clear aggression when health is low (REGARDLESS of immortality)
            BeastMasterMod.clearAggressionForLowHealth(livingEntity);

            // If immortal and health would drop below 1, cancel the damage
            if ((isPet || isMount) && livingEntity.getHealth() - amount <= 0) {
                livingEntity.setHealth(1.0F);
                cir.setReturnValue(false);
            }
        }
    }

    // NEW: Enhanced friendly fire prevention for ALL owned entities
    @Inject(method = "damage", at = @At("TAIL"))
    private void preventAllFriendlyRetaliation(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;

        // Only process for owned supported entities
        if (!BeastMasterMod.isOwned(livingEntity) ||
            (!BeastConfig.isSupportedPet(livingEntity) && !BeastConfig.isSupportedMount(livingEntity))) {
            return;
        }

        Entity attacker = source.getAttacker();
        if (attacker == null || attacker == livingEntity) {
            return;
        }

        // Check if attacker is a friend (same owner)
        UUID victimOwner = BeastMasterMod.getOwnerUuid(livingEntity);
        UUID attackerOwner = BeastMasterMod.getOwnerUuid(attacker);

        if (victimOwner != null && attackerOwner != null && victimOwner.equals(attackerOwner)) {
            // Prevent retaliation for ALL entity types

            // For MobEntities (wolves, llamas, etc.) - clear targets and anger
            if (livingEntity instanceof MobEntity mob) {
                // Don't target the attacker
                if (mob.getTarget() == attacker) {
                    mob.setTarget(null);
                }

                // Special handling for wolves
                if (livingEntity instanceof WolfEntity wolf) {
                    wolf.setAngryAt(null);
                }

                // Special handling for llamas - stop spitting at friends
                if (livingEntity instanceof net.minecraft.entity.passive.LlamaEntity llama) {
                    llama.setAttacking(false);
                }
            }

            // Also prevent the attacker from getting angry at the victim
            if (attacker instanceof MobEntity attackerMob) {
                if (attackerMob.getTarget() == livingEntity) {
                    attackerMob.setTarget(null);
                }

                if (attacker instanceof WolfEntity attackerWolf) {
                    attackerWolf.setAngryAt(null);
                }

                if (attacker instanceof net.minecraft.entity.passive.LlamaEntity attackerLlama) {
                    attackerLlama.setAttacking(false);
                }
            }
        }
    }

    private boolean isDamageFromFriend(DamageSource source, Entity entity) {
        Entity attacker = source.getAttacker();
        if (attacker == null) return false;

        // Cache owner UUID
        UUID ownerUuid = BeastMasterMod.getOwnerUuid(entity);
        if (ownerUuid == null) return false;

        if (attacker instanceof PlayerEntity player) {
            return player.getUuid().equals(ownerUuid);
        }

        // Check if damage is from another owned entity (pet or mount)
        if (BeastConfig.isSupportedPet(attacker) || BeastConfig.isSupportedMount(attacker)) {
            UUID attackerOwnerUuid = BeastMasterMod.getOwnerUuid(attacker);
            return ownerUuid.equals(attackerOwnerUuid);
        }

        return false;
    }
}
