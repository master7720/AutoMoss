package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AutoMossModule - A RusherHack module that automatically uses bone meal
 * on moss blocks and valid spreadable blocks (stone, dirt, grass, etc.),
 * replicating vanilla moss spreading behavior.
 * Performance:
 * - Uses a target block cache updated every few ticks (SCAN_INTERVAL).
 * - Prevents repeatedly bonemealing the same moss block using a cooldown map.
 */
public class AutoMossModule extends ToggleableModule {

    /**
     * Interval in ticks between rescanning blocks for valid targets.
     */
    private static final int SCAN_INTERVAL = 5;
    /** Maximum range (radius) in blocks to search for valid targets. */
    private final NumberSetting<Double> range = new NumberSetting<>("Range", 4.5, 1.0, 6.0);
    /** Cooldown in ticks before bonemealing the same moss block again. */
    private final NumberSetting<Integer> mossCooldown = new NumberSetting<>("Moss Cooldown", 100, 20, 200);
    /** Whether to allow bonemealing trees (azaleas, saplings, leaves). */
    private final BooleanSetting makeTrees = new BooleanSetting("Make Trees", true);
    /** Delay in ticks between bonemeal actions. */
    private final NumberSetting<Integer> delay = new NumberSetting<>("Delay", 2, 0, 20);
    /** Maximum number of bonemeal actions per tick. */
    private final NumberSetting<Integer> maxUsesPerTick = new NumberSetting<>("Max Uses/Tick", 1, 1, 5);
    /** Whether to rotate towards blocks before bonemealing. */
    private final BooleanSetting rotate = new BooleanSetting("Rotate", true);

    /** Tracks moss blocks recently bonemealed, mapped to their cooldown. */
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();
    /** Cache of valid target block positions within range. */
    private final Set<BlockPos> targetBlocks = new HashSet<>();
    /**
     * Timer for global bonemeal delay.
     */
    private int delayTimer = 0;
    /** Counter for when to rescan nearby blocks. */
    private int scanTimer = 0;

    public AutoMossModule() {
        super("AutoMoss", "Automatically uses bone meal on moss and valid spreadable blocks.", ModuleCategory.MISC);
        registerSettings(range, mossCooldown, makeTrees, delay, maxUsesPerTick, rotate);
    }

    /**
     * Handles the main tick logic:
     * - Finds bone meal slot.
     * - Updates cooldowns and cached target blocks.
     * - Attempts to bonemeal moss or spreadable blocks within range.
     */
    @Subscribe
    public void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        updateMossCooldowns();
        // rescan targets every SCAN_INTERVAL ticks
        if (scanTimer-- <= 0) {
            updateTargetBlocks();
            scanTimer = SCAN_INTERVAL;
        }

        int uses = 0;
        BlockPos playerPos = mc.player.blockPosition();
        double rangeSq = range.getValue() * range.getValue();

        for (BlockPos pos : targetBlocks) {
            if (pos.distSqr(playerPos) > rangeSq) continue;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            boolean isMoss = block == Blocks.MOSS_BLOCK;
            boolean isTreeTarget = makeTrees.getValue() && (
                    block == Blocks.AZALEA ||
                            block.getDescriptionId().toLowerCase().contains("sapling") ||
                            block.getDescriptionId().toLowerCase().contains("leaves")
            );

            if (!isMoss && !isTreeTarget) continue;
            if (isMoss && recentlyUsedMoss.containsKey(pos)) continue;

            // check if player is holding bonemeal in hand
            ItemStack main = mc.player.getMainHandItem();
            ItemStack off = mc.player.getOffhandItem();
            boolean holdingBoneMeal = main.getItem() instanceof BoneMealItem || off.getItem() instanceof BoneMealItem;

            // rotate to block before using bonemeal
            if (rotate.getValue() && holdingBoneMeal) {
                RusherHackAPI.getRotationManager().updateRotation(pos);
                BlockHitResult lookResult = RusherHackAPI.getRotationManager().getLookRaycast(pos);
                if (lookResult == null || lookResult.getType() == BlockHitResult.Type.MISS) continue;
            }

            // handles bonemeal is hand
            boolean placed = false;
            if (main.getItem() instanceof BoneMealItem) {
                RusherHackAPI.interactions().useBlock(pos, InteractionHand.MAIN_HAND, true, true);
                placed = true;
            } else if (off.getItem() instanceof BoneMealItem) {
                RusherHackAPI.interactions().useBlock(pos, InteractionHand.OFF_HAND, true, true);
                placed = true;
            }

            if (placed) {
                if (isMoss) recentlyUsedMoss.put(pos, mossCooldown.getValue());
                uses++;
                if (uses >= maxUsesPerTick.getValue()) break;
            }
        }

        if (uses > 0) delayTimer = delay.getValue();
    }

    /**
     * Updates the cache of valid target blocks around the player.
     */
    private void updateTargetBlocks() {
        targetBlocks.clear();
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(range.getValue());

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    Block block = state.getBlock();

                    if (isValidTarget(block)) targetBlocks.add(pos);
                }
            }
        }
    }

    /**
     * Checks if a block is a valid target for bonemealing.
     * Includes moss itself and all blocks in the MOSS_REPLACEABLE tag.
     */
    private boolean isValidTarget(Block block) {
        return block == Blocks.MOSS_BLOCK || block.defaultBlockState().is(BlockTags.MOSS_REPLACEABLE);
    }

    /**
     * Decrements cooldown timers for moss blocks and removes expired entries.
     */
    private void updateMossCooldowns() {
        recentlyUsedMoss.replaceAll((pos, cooldown) -> cooldown - 1);
        recentlyUsedMoss.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }
}