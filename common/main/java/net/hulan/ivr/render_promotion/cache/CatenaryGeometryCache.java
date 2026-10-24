package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.function.BiConsumer;

/**
 * Identifies MSD's RenderTrains callback lambdas and routes them through the
 * shared local geometry cache.  MSD supplies three callback interfaces
 * (ordinary, transition and rigid catenary); their final consumer contract is
 * identical after MTR's queue dispatch, so one adapter preserves layer order.
 */
public final class CatenaryGeometryCache {
    private CatenaryGeometryCache() { }

    /**
     * Lambda classes emitted by top.mcmtr's RenderTrainsMixin retain the
     * package name even after Fabric/Forge remapping.  MTR callbacks do not,
     * so this check avoids applying the MSD namespace to train/rail callbacks.
     */
    public static boolean isMsdCallback(BiConsumer<?, ?> callback) {
        if (callback == null) return false;
        String name = callback.getClass().getName();
        return name.contains("top.mcmtr") || name.contains("mcmtr.mixin");
    }

    public static void render(BiConsumer<Object, Object> callback,
                              PoseStack matrices, VertexConsumer consumer) {
        RenderCallbackGeometryCache.renderCatenary(callback, matrices, consumer);
    }
}
