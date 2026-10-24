package net.hulan.ivr.render_promotion.itf;

/**
 * 由客户端列车暴露的绘制提交跳过状态。
 * 不用于跳过车厢计算、声音或乘客状态。
 */
public interface TrainRenderSkipState {
    /**
     * 返回本次模拟是否应跳过不可见整列的绘制提交。
     */
    boolean ivr$skipDistantCars();
}
