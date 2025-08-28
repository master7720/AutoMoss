package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.utils.ColorUtils;
import org.rusherhack.client.api.utils.WorldUtils;
import org.rusherhack.core.utils.Timer;

import java.awt.*;
import java.util.*;

/**
 * AutoMossModule - A RusherHack module for Minecraft 1.21.4 that automates moss bonemealing,
 * tree growth, and optional grass breaking above moss blocks.
 *
 * Features:
 * - Automatically bonemeals moss blocks and growable trees/azaleas.
 * - Breaks blocking grass (short or tall) above moss if configured.
 * - Supports rotation and FOV-based targeting.
 * - Configurable swing hand (main, offhand, or both).
 * - Highlighting of current bonemeal target.
 * - Cooldown per moss block to prevent repeated bonemealing.
 * - Adjustable max actions per tick.
 * - Adjustable delay for breaking grass.
 */
public class AutoMossModule extends ToggleableModule {

    /** Maximum search distance for moss blocks. */
    private final NumberSetting<Float> range = new NumberSetting<>("Range",
            "Maximum search distance for moss blocks.", 4f, 1f, 5f).incremental(1f);

    /** Cooldown in ticks before a moss block can be bonemealed again. */
    private final NumberSetting<Integer> mossCooldown = new NumberSetting<>("MossCooldown",
            "Ticks to wait before bonemealing the same moss block again.", 100, 20, 200);

    /** Enable automatic bonemealing of saplings and azalea. */
    private final BooleanSetting makeTrees = new BooleanSetting("MakeTrees",
            "Automatically bonemeal saplings and azalea to grow trees.", true);

    /** Delay in ticks after performing a bonemeal or break action. */
    private final NumberSetting<Integer> delay = new NumberSetting<>("Delay",
            "Ticks to wait after performing an action.", 1, 0, 20);

    /** Maximum actions (bonemeal + break) allowed per tick. */
    private final NumberSetting<Integer> maxUsesPerTick = new NumberSetting<>("MaxUses/Tick",
            "Maximum actions (bonemeal + break) per tick.", 1, 1, 5);

    /** Enable breaking of grass (short/tall) above moss blocks. */
    private final BooleanSetting grassBreak = new BooleanSetting("GrassBreak",
            "Break grass (short or tall) above moss blocks if blocking growth.", true);

    /** Delay in ticks between breaking grass blocks to avoid instant breaking. */
    private final NumberSetting<Integer> grassBreakDelay = new NumberSetting<>("GrassBreakDelay",
            "Ticks to wait between breaking grass blocks.", 2, 0, 20);

    /** Enable rotation towards the target block before performing action. */
    private final BooleanSetting rotate = new BooleanSetting("Rotate",
            "Rotate player towards the target block before action.", true);

    /** Delay in ticks for rotation updates. */
    private final NumberSetting<Integer> rotateDelay = new NumberSetting<>("Rotate Delay",
            "Ticks to wait before rotating again.", 2, 1, 20);

    /** Only bonemeal blocks that are within the player's FOV. */
    private final BooleanSetting fov = new BooleanSetting("FOV",
            "Only bonemeal blocks that are within the player's FOV.", true);

    /** Maximum FOV angle in degrees for targeting blocks. */
    private final NumberSetting<Float> fovAngle = new NumberSetting<>("FOVAngle",
            "Maximum angle in degrees for FOV targeting.", 120f, 0f, 180f);

    /** Enable rendering of target highlights. */
    private final BooleanSetting render = new BooleanSetting("Render", true);

    /** Color used to highlight target blocks. */
    private final ColorSetting color = new ColorSetting("Color", ColorUtils.transparency(Color.RED, 0.25f)).setThemeSync(true);

    /** Enable debug messages for actions like bonemealing or grass breaking. */
    private final BooleanSetting debug = new BooleanSetting("Debug",
            "Print debug messages for actions (bonemeal, block break, etc.)", true);

    /** Duration in ticks to highlight the current bonemeal target. */
    private static final int HIGHLIGHT_DURATION = 5;

    /** Cooldown tracking map for moss blocks. */
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();

    /** Current set of target blocks to bonemeal. */
    private final Set<BlockPos> targetBlocks = new HashSet<>();

    /** The currently selected bonemeal target. */
    private BlockPos currentBonemealTarget;

    /** Timer for action delay. */
    private final Timer delayTimer = new Timer();

    /** Timer for grass breaking delay. */
    private final Timer grassBreakTimer = new Timer();

    /** Ticks remaining to highlight the target. */
    private int highlightTicks;

    /** Handles rotation delays between updates. */
    private final RotationDelayHandler rotationDelayHandler = new RotationDelayHandler(rotateDelay);

    /**
     * Initializes the AutoMoss module and registers settings.
     */
    public AutoMossModule() {
        super("AutoMoss", "Automatically bonemeals moss, trees, and optionally breaks grass above.", ModuleCategory.MISC);
        this.registerSettings(range, mossCooldown, makeTrees, delay, maxUsesPerTick,
                grassBreak, grassBreakDelay, rotate, rotateDelay, fov, fovAngle, color, debug);
    }

    /**
     * Main update event, handles scanning, bonemealing, rotation, and grass breaking.
     *
     * @param event Update event
     */
    @Subscribe
    private void onUpdate(EventUpdate event) {
        boolean hasMain = mc.player.getMainHandItem().getItem() instanceof BoneMealItem;
        boolean hasOff = mc.player.getOffhandItem().getItem() instanceof BoneMealItem;
        if (!hasMain && !hasOff) return;

        targetBlocks.clear();
        targetBlocks.addAll(WorldUtils.getSphere(mc.player.blockPosition(), range.getValue(), this::isTarget));

        int actionsUsed = 0;
        BlockPos playerPos = mc.player.blockPosition();
        Vec3 playerEye = mc.player.getEyePosition(1f);
        Vec3 lookVec = mc.player.getLookAngle().normalize();

        PriorityQueue<BlockPos> sortedTargets = new PriorityQueue<>(Comparator.comparingDouble(pos -> pos.distSqr(playerPos)));
        sortedTargets.addAll(targetBlocks);

        while (!sortedTargets.isEmpty() && actionsUsed < maxUsesPerTick.getValue()) {
            BlockPos pos = sortedTargets.poll();

            if (fov.getValue() && !isInFov(pos, playerEye, lookVec)) continue;

            Block block = mc.level.getBlockState(pos).getBlock();
            boolean isMoss = block == Blocks.MOSS_BLOCK;
            boolean isTreeTarget = makeTrees.getValue() && isTreeBlock(block);
            if ((!isMoss && !isTreeTarget) || (isMoss && recentlyUsedMoss.containsKey(pos))) continue;

            currentBonemealTarget = pos;

            // Handle grass break with delay
            if (isMoss && handleGrassBreak(pos)) continue;

            // Use rotation + raycast
            final BlockHitResult hitResult = RusherHackAPI.getRotationManager().getLookRaycast(currentBonemealTarget);
            if(hitResult == null || hitResult.getType() == BlockHitResult.Type.MISS) continue;

            if (rotate.getValue() && rotationDelayHandler.shouldRotate()) {
                RusherHackAPI.getRotationManager().updateRotation(currentBonemealTarget);
            }

            if (delayTimer.ticksPassed(delay.getValue()) && useBoneMeal(hitResult)) {
                actionsUsed++;
                delayTimer.reset();
            }
        }

        recentlyUsedMoss.replaceAll((p, cooldown) -> cooldown - 1);
        recentlyUsedMoss.entrySet().removeIf(entry -> entry.getValue() <= 0);

        if (highlightTicks > 0 && --highlightTicks <= 0) currentBonemealTarget = null;
    }

    /**
     * Checks if a block is a valid target (moss or tree).
     *
     * @param pos Block position
     * @return true if block is moss or tree target
     */
    private boolean isTarget(BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        return block == Blocks.MOSS_BLOCK || (makeTrees.getValue() && isTreeBlock(block));
    }

    /**
     * Checks if a block is a tree target (sapling, azalea, or leaves).
     *
     * @param block Block
     * @return true if block is tree-related
     */
    private boolean isTreeBlock(Block block) {
        return block == Blocks.AZALEA || block instanceof SaplingBlock || block.defaultBlockState().is(BlockTags.LEAVES);
    }

    /**
     * Attempts to bonemeal a block using main and offhand.
     *
     * @param hitResult Raycast hit result
     * @return true if bonemeal was applied
     */
    private boolean useBoneMeal(BlockHitResult hitResult) {
        boolean used = false;
        if (mc.player.getMainHandItem().getItem() instanceof BoneMealItem) {
            used |= mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult) == InteractionResult.SUCCESS;
        }
        if (mc.player.getOffhandItem().getItem() instanceof BoneMealItem) {
            used |= mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hitResult) == InteractionResult.SUCCESS;
        }

        if (used) {
            BlockPos pos = hitResult.getBlockPos();
            if (mc.level.getBlockState(pos).getBlock() == Blocks.MOSS_BLOCK) recentlyUsedMoss.put(pos, mossCooldown.getValue());
            highlightTicks = HIGHLIGHT_DURATION;
            debugPrint("Bonemealed: " + pos);
        }

        return used;
    }

    /**
     * Handles breaking grass above moss with delay.
     *
     * @param mossPos Moss block position
     * @return true if grass was broken
     */
    private boolean handleGrassBreak(BlockPos mossPos) {
        if (!grassBreak.getValue()) return false;
        if (!grassBreakTimer.ticksPassed(grassBreakDelay.getValue())) return false;

        BlockPos above = mossPos.above();
        BlockState state = mc.level.getBlockState(above);
        if ((state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))
                && above.distSqr(mc.player.blockPosition()) <= range.getValue() * range.getValue()) {
            mc.gameMode.destroyBlock(above);
            grassBreakTimer.reset();
            debugPrint("Broke grass: " + above);
            return true;
        }
        return false;
    }

    /**
     * Checks if the world and player are ready for operations.
     *
     * @return true if world is ready
     */
    private boolean isWorldReady() {
        return mc.player != null && mc.level != null;
    }

    /**
     * Checks if a block is inside the player's FOV.
     *
     * @param pos Block position
     * @param playerEye Player eye position
     * @param lookVec Player look vector
     * @return true if block is within FOV
     */
    private boolean isInFov(BlockPos pos, Vec3 playerEye, Vec3 lookVec) {
        Vec3 dir = Vec3.atCenterOf(pos).subtract(playerEye).normalize();
        return Math.toDegrees(Math.acos(lookVec.dot(dir))) <= fovAngle.getValue();
    }

    /**
     * Render event to highlight target blocks.
     *
     * @param event Render event
     */
    @Subscribe
    private void onRender3D(EventRender3D event) {
        if (!render.getValue() || targetBlocks.isEmpty()) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());
        for (BlockPos target : targetBlocks) {
            renderer.drawBox(target, target.equals(currentBonemealTarget), true, color.getValueRGB());
        }
        renderer.end();
    }

    /**
     * Prints debug messages if enabled.
     *
     * @param msg Message to print
     */
    private void debugPrint(String msg) {
        if (debug.getValue()) ChatUtils.print("[AutoMoss] " + msg);
    }

    /**
     * Helper class for rotation delay management.
     */
    private static class RotationDelayHandler {
        private int counter = 0;
        private final NumberSetting<Integer> rotateDelay;

        public RotationDelayHandler(NumberSetting<Integer> rotateDelay) {
            this.rotateDelay = rotateDelay;
        }

        /** Determines if rotation should occur based on delay ticks. */
        public boolean shouldRotate() {
            if (++counter >= rotateDelay.getValue()) {
                counter = 0;
                return true;
            }
            return false;
        }
    }
}