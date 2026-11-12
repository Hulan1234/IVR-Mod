package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** 1.16.5 does not expose default-color methods on VertexConsumer. */
public final class VertexConsumerCompatImpl {

    private VertexConsumerCompatImpl() {
    }

    public static void defaultColor(VertexConsumer delegate, int red, int green, int blue, int alpha) {
    }

    public static void unsetDefaultColor(VertexConsumer delegate) {
    }
}
