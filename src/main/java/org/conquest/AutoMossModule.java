package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import java.util.*;

public class AutoMossModule extends ToggleableModule {
    private final NumberSetting<Double> range = new NumberSetting<>("Range", 4.5, 1.0, 6.0);
    private final NumberSetting<Integer> mossSpreadCooldown = new NumberSetting<>("Moss Cooldown", 100, 20, 200);
    private final BooleanSetting makeTrees = new BooleanSetting("Make Trees", true);
    private final BooleanSetting inventoryAllow = new BooleanSetting("Inventory Allow", true);
    private final NumberSetting<Integer> delay = new NumberSetting<>("Delay", 2, 0, 20);
    private final NumberSetting<Integer> maxUsesPerTick = new NumberSetting<>("Max Uses/Tick", 1, 1, 5);

     int delayTimer = 0;
     final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();

    public AutoMossModule() {
        super("AutoMoss", "Automatically uses bone meal on moss and trees.", ModuleCategory.MISC);
        this.registerSettings(this.range, this.mossSpreadCooldown, this.makeTrees, this.inventoryAllow, this.delay, this.maxUsesPerTick);


    }

    @Subscribe(stage = Stage.ALL)
public void tick(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        updateMossCooldowns();

        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;

        int uses = 0;
        for (BlockPos pos : findTargets()) {
            if (uses >= maxUsesPerTick.getValue()) break;

            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();
            boolean isMoss = block.getDescriptionId().contains("moss_block");

            if (isMoss && recentlyUsedMoss.containsKey(pos)) continue;

            Vec3 hitPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);

            int prevSlot = mc.player.getInventory().selected;
            mc.player.getInventory().selected = boneMealSlot;

            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);

            mc.player.getInventory().selected = prevSlot;

            if (isMoss) {
                recentlyUsedMoss.put(pos, mossSpreadCooldown.getValue());
            }

            uses++;
            delayTimer = delay.getValue();
        }
    }

    private void updateMossCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> it = recentlyUsedMoss.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) it.remove();
            else entry.setValue(cooldown);
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.level == null) return targets;

        double rangeSq = range.getValue() * range.getValue();
        BlockPos playerPos = mc.player.blockPosition();

        for (int x = (int) -range.getValue(); x <= range.getValue(); x++) {
            for (int y = (int) -range.getValue(); y <= range.getValue(); y++) {
                for (int z = (int) -range.getValue(); z <= range.getValue(); z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    if (pos.distSqr(playerPos) > rangeSq) continue;

                    BlockState state = mc.level.getBlockState(pos);
                    String name = state.getBlock().getDescriptionId().toLowerCase();

                    if (makeTrees.getValue()) {
                        boolean isAzalea = name.contains("azalea") && !name.contains("tree");
                        boolean isSapling = name.contains("sapling");
                        if (isAzalea || isSapling) {
                            targets.add(pos);
                            continue;
                        }
                    }

                    if (name.contains("moss_block")) {
                        targets.add(pos);
                    }
                }
            }
        }

        return targets;
    }

    private int findBoneMealSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof BoneMealItem) {
                return i;
            }
        }
        return -1;
    }
}