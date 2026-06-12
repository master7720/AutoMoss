package org.conquest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer3D;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.WorldUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.utils.ColorUtils;
import org.rusherhack.core.utils.Timer;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;


public class AutoMossModule extends ToggleableModule {

    private final NumberSetting<Integer> delay =
            new NumberSetting<>("Delay", 1, 0, 20);

    private final NumberSetting<Integer> range =
            new NumberSetting<>("Range", 8, 1, 16);

    private final BooleanSetting rotate =
            new BooleanSetting("Rotate", true);

    private final BooleanSetting airAboveOnly =
            new BooleanSetting("AirAboveOnly", true);

    private final BooleanSetting render =
            new BooleanSetting("Render", true);

    private final ColorSetting color =
            new ColorSetting("Color", ColorUtils.transparency(Color.GREEN, 0.25f)).setThemeSync(true);

    private final List<BlockPos> targetBlocks = new ArrayList<>();
    private final Timer delayTimer = new Timer();

    private BlockPos currentTarget;

    public AutoMossModule() {
        super("AutoMoss", "Automatically bonemeals moss blocks", ModuleCategory.PLAYER);

        this.render.addSubSettings(color);

        this.registerSettings(delay, range, rotate, airAboveOnly, render);
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {

        targetBlocks.clear();

        targetBlocks.addAll(
                WorldUtils.getSphere(
                        mc.player.blockPosition(),
                        range.getValue(),
                        this::canUseBonemeal
                )
        );

        if (!mc.player.getMainHandItem().is(net.minecraft.world.item.Items.BONE_MEAL)) {
            currentTarget = null;
            return;
        }

        currentTarget = getBestTarget();

        if (currentTarget == null) {
            return;
        }

        if (rotate.getValue()) {
            RusherHackAPI.getRotationManager().updateRotation(currentTarget);
        }

        BlockHitResult hitResult;

        if (rotate.getValue()) {
            hitResult = RusherHackAPI.getRotationManager().getLookRaycast(currentTarget);
        } else {
            hitResult = new BlockHitResult(
                    currentTarget.getCenter(),
                    net.minecraft.core.Direction.UP,
                    currentTarget,
                    false
            );
        }

        if (hitResult == null || hitResult.getType() == BlockHitResult.Type.MISS) {
            return;
        }

        if (delayTimer.ticksPassed(delay.getValue())) {
            mc.gameMode.useItemOn(
                    mc.player,
                    InteractionHand.MAIN_HAND,
                    hitResult
            );

            delayTimer.reset();
        }
    }

    @Subscribe
    private void onRender3D(EventRender3D event) {

        if (!render.getValue() || targetBlocks.isEmpty()) {
            return;
        }

        IRenderer3D renderer = event.getRenderer();

        renderer.begin(event.getMatrixStack());

        for (BlockPos target : targetBlocks) {
            renderer.drawBox(
                    target,
                    target.equals(currentTarget),
                    true,
                    color.getValueRGB()
            );
        }

        renderer.end();
    }

    @Nullable
    private BlockPos getBestTarget() {

        BlockPos bestTarget = null;
        double closestDistance = Double.MAX_VALUE;

        for (BlockPos target : targetBlocks) {

            double distance =
                    mc.player.distanceToSqr(target.getCenter());

            if (distance < closestDistance) {
                closestDistance = distance;
                bestTarget = target;
            }
        }

        return bestTarget;
    }

    private boolean canUseBonemeal(BlockPos pos) {

        BlockState state = mc.level.getBlockState(pos);

        if (!state.is(Blocks.MOSS_BLOCK)) {
            return false;
        }

        if (airAboveOnly.getValue()) {
            return mc.level.getBlockState(pos.above()).isAir();
        }

        return true;
    }
}