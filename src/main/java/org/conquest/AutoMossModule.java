package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AutoMossModule extends ToggleableModule {
    private final NumberSetting<Double> range = new NumberSetting<>("Range", 4.5, 1.0, 6.0);
    private final NumberSetting<Integer> mossCooldown = new NumberSetting<>("Moss Cooldown", 100, 20, 200);
    private final BooleanSetting makeTrees = new BooleanSetting("Make Trees", true);
    private final BooleanSetting inventoryAllow = new BooleanSetting("Inventory Allow", true);
    private final NumberSetting<Integer> delay = new NumberSetting<>("Delay", 2, 0, 20);
    private final NumberSetting<Integer> maxUsesPerTick = new NumberSetting<>("Max Uses/Tick", 1, 1, 5);
    private final BooleanSetting rotate = new BooleanSetting("Rotate", true);

    private int delayTimer = 0;
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();
    private final Set<BlockPos> targetBlocks = new HashSet<>();

    private static final int SCAN_INTERVAL = 5;
    private int scanTimer = 0;

    public AutoMossModule() {
        super("AutoMoss", "Automatically uses bone meal on moss and valid spreadable blocks.", ModuleCategory.MISC);
        registerSettings(range, mossCooldown, makeTrees, inventoryAllow, delay, maxUsesPerTick, rotate);
    }

    @Subscribe(stage = Stage.ALL)
    public void onTick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;

        updateMossCooldowns();

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

            Vec3 hitPos = Vec3.atCenterOf(pos);
            BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);

            if (rotate.getValue()) {
                RusherHackAPI.getRotationManager().updateRotation(pos);
                BlockHitResult lookResult = RusherHackAPI.getRotationManager().getLookRaycast(pos);
                if (lookResult == null || lookResult.getType() == BlockHitResult.Type.MISS) continue;
            }

            int prevSlot = mc.player.getInventory().selected;
            mc.player.getInventory().selected = boneMealSlot;
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.getInventory().selected = prevSlot;

            if (isMoss) {
                recentlyUsedMoss.put(pos, mossCooldown.getValue());
            }

            uses++;
            if (uses >= maxUsesPerTick.getValue()) break;
        }

        if (uses > 0) {
            delayTimer = delay.getValue();
        }
    }

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

                    if (isValidTarget(block)) {
                        targetBlocks.add(pos);
                    }
                }
            }
        }
    }

    private boolean isValidTarget(Block block) {
        return block == Blocks.MOSS_BLOCK ||
                block.defaultBlockState().is(BlockTags.MOSS_REPLACEABLE);
    }

    private void updateMossCooldowns() {
        recentlyUsedMoss.replaceAll((pos, cooldown) -> cooldown - 1);
        recentlyUsedMoss.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private int findBoneMealSlot() {
        if (inventoryAllow.getValue()) {
            for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
                if (mc.player.getInventory().getItem(i).getItem() instanceof BoneMealItem) return i;
            }
        } else {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).getItem() instanceof BoneMealItem) return i;
            }
        }
        return -1;
    }
}
