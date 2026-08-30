package net.hulan.ivr.mixin;

import mtr.data.LiftClient;
import net.hulan.ivr.utils.TrainRenderOptimize;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 电梯渲染优化 Mixin。
 *
 * 目的：让电梯（Lift）复用列车渲染的距离剔除逻辑。
 * 原版 MTR 的电梯（LiftClient.tickClient）每帧都会绘制所有电梯（电梯箱体 + 门 + 面板），
 * 远处/屏幕外的电梯同样全量提交，增加渲染线程顶点生成负担。
 *
 * 做法：拦截 LiftClient.tickClient，在 renderLift（电梯绘制回调）调用之前，
 * 用反射读取电梯当前位置（父类 Lift 的 currentPositionX/Y/Z），判断到相机距离：
 *   - 距离 ≤ TRAIN_RENDER_DISTANCE（300 格）：正常渲染；
 *   - 距离 > 300 格：cancel() 跳过电梯绘制（tick 电梯逻辑已在前面执行，电梯仍正常移动）。
 * 只做距离剔除，不做半透明批次（电梯渲染不走列车批次）。
 *
 * 注：LiftClient.tickClient 的 tick()（电梯移动/开门逻辑）在渲染之前执行，
 * 因此 cancel() 只会跳过绘制，不影响电梯行为。
 * currentPositionX/Y/Z 是父类 Lift 的 protected 字段，无法直接 @Shadow（父类字段运行时定位失败），
 * 故用反射读取。
 */
@Mixin(LiftClient.class)
public abstract class LiftRenderOptimizeMixin {

    /**
     * 注入到 LiftClient.tickClient 中 renderLift 调用之前。
     * tickClient 流程：tick（电梯逻辑）→ renderPlayerAndGetOffset → 计算位置 → renderLift（绘制）。
     * 在此注入点取消只跳过绘制，tick 已执行完毕。
     *
     * @param world      电梯所在世界
     * @param renderLift MTR 渲染回调（接口）
     * @param tickDelta  渲染插值
     * @param ci         回调，可 cancel() 取消原方法执行
     */
    @Inject(method = "tickClient",
            at = @At(value = "INVOKE",
                    target = "Lmtr/data/LiftClient$RenderLift;renderLift(DDDFF)V",
                    shift = At.Shift.BEFORE),
            cancellable = true, remap = false)
    private void ivr$cullLift(Level world, LiftClient.RenderLift renderLift, float tickDelta, CallbackInfo ci) {
        // 用反射读取父类 Lift 的 currentPositionX/Y/Z（父类字段无法直接 @Shadow）
        double[] pos = TrainRenderOptimize.getLiftPosition((LiftClient) (Object) this);
        if (pos == null) {
            return;
        }
        // 电梯中心相对相机的坐标
        Vec3 rel = TrainRenderOptimize.toCameraRelative(pos[0], pos[1], pos[2], false);
        if (rel == null) {
            return;
        }
        // 距离 > 300 格 → 跳过电梯绘制（复用列车渲染距离阈值）
        if (rel.length() > TrainRenderOptimize.getTrainRenderDistance()) {
            ci.cancel();
        }
    }
}
