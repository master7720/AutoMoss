package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.awt.*;
import java.util.*;

/**
 * AutoMossModule - A RusherHack module for Minecraft 1.21.4 that automates moss bonemealing,
 * tree growth, and optional grass breaking above moss blocks.
 *
 * Features:
 * - Automatically bonemeals moss blocks and growable trees/azaleas
 * - Breaks blocking grass (short or tall) above moss if configured
 * - Supports rotation and FOV-based targeting
 * - Configurable swing hand (main, offhand, or both)
 * - Highlighting of current bonemeal target
 * - Cooldown per moss block to prevent repeated bonemealing
 * - Adjustable max actions per tick
 */
public class AutoMossModule extends ToggleableModule {

    // === Constants ===
    private static final int SCAN_INTERVAL = 5;        // Ticks between target scans
    private static final int HIGHLIGHT_DURATION = 5;   // Ticks to show highlight

    // === Settings ===
    private final NumberSetting<Double> range = new NumberSetting<>("Range",
            "Maximum search distance for moss blocks.", 4.5, 1.0, 6.0);

    private final NumberSetting<Integer> mossCooldown = new NumberSetting<>("MossCooldown",
            "Ticks to wait before bonemealing the same moss block again.", 100, 20, 200);

    private final BooleanSetting makeTrees = new BooleanSetting("MakeTrees",
            "Automatically bonemeal saplings and azalea to grow trees.", true);

    private final NumberSetting<Integer> delay = new NumberSetting<>("Delay",
            "Ticks to wait after performing an action.", 3, 1, 20);

    private final NumberSetting<Integer> maxUsesPerTick = new NumberSetting<>("MaxUses/Tick",
            "Maximum actions (bonemeal + break) per tick.", 1, 1, 5);

    private final BooleanSetting grassBreak = new BooleanSetting("GrassBreak",
            "Break grass (short or tall) above moss blocks if blocking growth.", true);

    // --- Rotation & FOV ---
    private final BooleanSetting rotate = new BooleanSetting("Rotate",
            "Rotate player towards the target block before action.", true);

    private final NumberSetting<Float> rotationSpeed = new NumberSetting<>("RotationSpeed",
            "Maximum degrees per tick when rotating.", 10f, 1f, 30f);

    private final BooleanSetting fov = new BooleanSetting("FOV",
            "Only bonemeal blocks that are within the player's FOV.", true);

    private final NumberSetting<Float> fovAngle = new NumberSetting<>("FOVAngle",
            "Maximum angle in degrees for FOV targeting.", 120f, 0f, 180f);

    private final EnumSetting<SwingMode> swing = new EnumSetting<>("Swing",
            "Hand to use for bonemealing.", SwingMode.MAIN);

    private final ColorSetting color = new ColorSetting("Highlight",
            "Highlight color for the current bonemeal target.", new Color(0, 255, 0, 123));

    private final BooleanSetting debug = new BooleanSetting("Debug",
            "Print debug messages for actions (bonemeal, block break, etc.)", true);

    // === State ===
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>(); // Tracks moss block cooldowns
    private final Set<BlockPos> targetBlocks = new HashSet<>();             // Scanned moss/tree targets
    private int delayTimer;                                                 // Timer for action delay
    private int scanTimer;                                                  // Timer for target scanning
    private BlockPos currentBonemealTarget;                                 // Current highlighted block
    private int highlightTicks;                                             // Ticks remaining for highlight

    /**
     * Constructor: Registers settings for the module.
     */
    public AutoMossModule() {
        super("AutoMoss", "Automatically bonemeals moss, trees, and optionally breaks grass above.", ModuleCategory.MISC);
        registerSettings(range, mossCooldown, makeTrees, delay, maxUsesPerTick,
                rotate, rotationSpeed, grassBreak, fov, fovAngle, swing, color, debug);
    }

    /**
     * Tick handler for the module. Called every game tick.
     * Scans for moss/tree targets, rotates, breaks grass, and bonemeals.
     *
     * @param event EventUpdate object (tick update)
     */
    @Subscribe
    public void onTick(EventUpdate event) {
        if (!isWorldReady() || delayTimer > 0) {
            if (delayTimer > 0) delayTimer--;
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        Vec3 playerEye = mc.player.getEyePosition(1f);
        Vec3 lookVec = mc.player.getLookAngle().normalize();
        int actionsUsed = 0;
        double reachSq = Math.pow(range.getValue(), 2) + 0.25;

        boolean hasMain = mc.player.getMainHandItem().getItem() instanceof BoneMealItem;
        boolean hasOff = mc.player.getOffhandItem().getItem() instanceof BoneMealItem;
        if (!hasMain && !hasOff) return;

        // Scan nearby blocks for valid targets
        scanTargets(playerPos, reachSq);

        // Process targets (rotation, grass break, bonemeal)
        actionsUsed = processTargets(playerPos, playerEye, lookVec, actionsUsed, reachSq, hasMain, hasOff);

        // Update highlight timer
        if (highlightTicks > 0 && --highlightTicks <= 0) currentBonemealTarget = null;
    }

    /**
     * Scans for moss and tree targets within range.
     *
     * @param playerPos Player's current block position
     * @param reachSq   Maximum squared distance for scanning
     */
    private void scanTargets(BlockPos playerPos, double reachSq) {
        if (scanTimer-- > 0) return;

        targetBlocks.clear();
        scanTimer = SCAN_INTERVAL;
        int r = (int) Math.ceil(range.getValue());

        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    Block block = mc.level.getBlockState(pos).getBlock();

                    // Reduce cooldowns for moss
                    recentlyUsedMoss.computeIfPresent(pos, (p, cooldown) -> cooldown - 1);
                    recentlyUsedMoss.entrySet().removeIf(entry -> entry.getValue() <= 0);

                    if (pos.distSqr(playerPos) <= reachSq && isTarget(block)) {
                        targetBlocks.add(pos);
                    }
                }
            }
        }

        debugPrint("Target scan updated. Total targets: " + targetBlocks.size());
    }

    /**
     * Processes all scanned targets: rotates, breaks grass, bonemeals.
     *
     * @param playerPos   Player block position
     * @param playerEye   Player eye position
     * @param lookVec     Player look vector
     * @param actionsUsed Actions used so far this tick
     * @param reachSq     Maximum squared range
     * @param hasMain     Whether player has bonemeal in main hand
     * @param hasOff      Whether player has bonemeal in offhand
     * @return Updated actionsUsed value
     */
    private int processTargets(BlockPos playerPos, Vec3 playerEye, Vec3 lookVec,
                               int actionsUsed, double reachSq, boolean hasMain, boolean hasOff) {
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

            if (isMoss && handleGrassBreak(pos)) continue;

            if (rotate.getValue() && actionsUsed < maxUsesPerTick.getValue()) rotateTo(pos);

            if (actionsUsed < maxUsesPerTick.getValue() && useBoneMeal(pos, hasMain, hasOff, isMoss)) {
                actionsUsed++;
                delayTimer = delay.getValue();
            }
        }

        return actionsUsed;
    }

    /**
     * Determines if a block is a valid target (moss or tree block).
     */
    private boolean isTarget(Block block) {
        return block == Blocks.MOSS_BLOCK || block.defaultBlockState().is(BlockTags.MOSS_REPLACEABLE)
                || (makeTrees.getValue() && isTreeBlock(block));
    }

    private boolean isTreeBlock(Block block) {
        String id = block.getDescriptionId().toLowerCase();
        return block == Blocks.AZALEA || id.contains("sapling") || id.contains("leaves");
    }

    /**
     * Rotates the player towards a target block.
     *
     * @param pos Target block position
     * @return true if rotation is successful
     */
    private boolean rotateTo(BlockPos pos) {
        currentBonemealTarget = pos;
        RusherHackAPI.getRotationManager().updateRotation(pos);
        BlockHitResult hitResult = RusherHackAPI.getRotationManager().getLookRaycast(pos);
        return hitResult != null && hitResult.getType() != BlockHitResult.Type.MISS;
    }

    /**
     * Uses bone meal on a block using the configured hand(s).
     */
    private boolean useBoneMeal(BlockPos pos, boolean hasMain, boolean hasOff, boolean isMoss) {
        boolean bonemealed = false;

        switch (swing.getValue()) {
            case MAIN -> {
                if (hasMain)
                    bonemealed = RusherHackAPI.interactions().useBlock(pos, InteractionHand.MAIN_HAND, true, true);
            }
            case OFFHAND -> {
                if (hasOff)
                    bonemealed = RusherHackAPI.interactions().useBlock(pos, InteractionHand.OFF_HAND, true, true);
            }
            case BOTH -> {
                if (hasMain)
                    bonemealed |= RusherHackAPI.interactions().useBlock(pos, InteractionHand.MAIN_HAND, true, true);
                if (hasOff)
                    bonemealed |= RusherHackAPI.interactions().useBlock(pos, InteractionHand.OFF_HAND, true, true);
            }
        }

        if (bonemealed) {
            if (isMoss) recentlyUsedMoss.put(pos, mossCooldown.getValue());
            highlightTicks = HIGHLIGHT_DURATION;
            debugPrint("Bonemealed: " + pos);
        }

        return bonemealed;
    }

    /**
     * Breaks grass above moss if blocking growth.
     *
     * @param mossPos Position of moss block
     * @return true if a block was broken
     */
    private boolean handleGrassBreak(BlockPos mossPos) {
        if (!grassBreak.getValue()) return false;

        BlockPos above = mossPos.above();
        double maxRange = range.getValue();
        BlockState state = mc.level.getBlockState(above);

        if ((state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) &&
                above.distSqr(mc.player.blockPosition()) <= maxRange * maxRange) {
            breakBlock(above);
            debugPrint("Broke grass: " + above);
            return true;
        }
        return false;
    }

    /**
     * Breaks a block safely using the configured swing hand.
     */
    private void breakBlock(BlockPos pos) {
        try {
            switch (swing.getValue()) {
                case MAIN -> mc.player.swing(InteractionHand.MAIN_HAND);
                case OFFHAND -> mc.player.swing(InteractionHand.OFF_HAND);
                case BOTH -> {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.OFF_HAND);
                }
            }
            mc.gameMode.destroyBlock(pos);
        } catch (Exception e) {
            debugPrint("Failed to break block at " + pos + ": " + e.getMessage());
        }
    }

    private boolean isWorldReady() {
        return mc.player != null && mc.level != null;
    }

    private boolean isInFov(BlockPos pos, Vec3 playerEye, Vec3 lookVec) {
        Vec3 dir = Vec3.atCenterOf(pos).subtract(playerEye).normalize();
        return Math.toDegrees(Math.acos(lookVec.dot(dir))) <= fovAngle.getValue();
    }

    @Subscribe
    public void onRender3D(EventRender3D event) {
        if (!isWorldReady() || currentBonemealTarget == null) return;

        IRenderer3D renderer = event.getRenderer();
        renderer.begin(event.getMatrixStack());
        renderer.setDepthTest(true);
        renderer.setLineWidth(2f);
        renderer.drawBox(currentBonemealTarget, true, true, color.getValue().getRGB());
        renderer.end();
    }

    private void debugPrint(String msg) {
        if (debug.getValue()) ChatUtils.print("[AutoMoss] " + msg);
    }

    /**
     * SwingMode enum for specifying which hand(s) to use.
     */
    public enum SwingMode {MAIN, OFFHAND, BOTH}
}