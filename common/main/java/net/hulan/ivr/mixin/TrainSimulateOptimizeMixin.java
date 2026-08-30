package net.hulan.ivr.mixin;

import mtr.data.TrainClient;
import mtr.path.PathData;
import net.hulan.ivr.utils.TrainRenderOptimize;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 列车模拟/渲染距离剔除 Mixin。
 *
 * 目的：解决"线网上列车 >5 辆就卡顿"的问题。
 * MTR 原版 RenderTrains.render 每帧对所有 TrainClient 无条件调用 simulateTrain（路径/速度推进、
 * 车厢位置计算、声音、渲染准备），无论列车离相机多远。线网列车一多，CPU 全被远处列车占用。
 *
 * 做法：拦截 TrainClient.simulateTrain，在其 HEAD 处用列车路径首节点（path[0].startingPos）
 * 作为列车粗略位置，判断到相机的距离：
 *   - 距离 ≤ TRAIN_RENDER_DISTANCE（150）+ TRAIN_SIMULATE_BUFFER（64）= 214 格：正常模拟+渲染；
 *   - 距离 > 214 格：ci.cancel() 完全跳过模拟与渲染（零开销）。
 * 玩家乘坐的列车（玩家 UUID 在 Train.ridingEntities 中）永不跳过，保证玩家始终看到自己乘坐的车。
 *
 * 注：跳过 simulateTrain 会连带跳过 renderCar（渲染在 simulateCar 内），
 * 因此远车既不模拟也不渲染，显著降低多列车场景的 CPU 负载。
 */
@Mixin(TrainClient.class)
public abstract class TrainSimulateOptimizeMixin {

    /**
     * 注入到 TrainClient.simulateTrain 开头。
     * 在 MTR 全量模拟之前，先做距离判断，远车直接跳过。
     *
     * @param world             列车所在世界
     * @param tickDelta         渲染插值
     * @param speedCallback     速度回调
     * @param announcementCallback    到站播报回调
     * @param lightRailAnnouncementCallback 轻轨播报回调
     * @param ci                回调，可 cancel() 取消原方法执行
     */
    @Inject(method = "simulateTrain",
            at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ivr$cullTrain(Level world, float tickDelta, TrainClient.SpeedCallback speedCallback, TrainClient.AnnouncementCallback announcementCallback, TrainClient.AnnouncementCallback lightRailAnnouncementCallback, CallbackInfo ci) {
        // 玩家乘坐的列车永不跳过（玩家在车上必须模拟+渲染整列）
        if (TrainRenderOptimize.isPlayerOnTrain((TrainClient) (Object) this)) { // 玩家乘坐时必须继续模拟列车。
            return; // 保证玩家所在列车不会被远距离优化跳过。
        }
        // 用反射读取父类 Train 的 path 字段（父类字段无法直接 @Shadow）
        List<PathData> path = TrainRenderOptimize.getTrainPath((TrainClient) (Object) this); // 获取列车路径快照。
        if (path == null || path.isEmpty()) {
            return;
        }
        BlockPos startPos = path.get(0).startingPos; // 使用路径首点近似列车位置。
        if (startPos == null) {
            return;
        }
        // 到相机的相对坐标
        Vec3 rel = TrainRenderOptimize.toCameraRelative(startPos); // 计算路径首点相对相机的位置。
        if (rel == null) {
            return;
        }
        // 距离 > 渲染距离 + 缓冲 → 完全跳过模拟与渲染
        double skipDistance = TrainRenderOptimize.getTrainRenderDistance() + TrainRenderOptimize.TRAIN_SIMULATE_BUFFER; // 为模拟范围加入缓冲距离。
        if (rel.lengthSqr() > skipDistance * skipDistance) { // 判断列车是否远到可以跳过模拟。
            ci.cancel(); // 跳过远处列车的模拟和渲染准备。
        }
    }
}
