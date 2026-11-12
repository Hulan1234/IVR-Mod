package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class VertexConsumerCompatImpl {

    private VertexConsumerCompatImpl() {
    }

    public static void defaultColor(VertexConsumer delegate, int red, int green, int blue, int alpha) {
        delegate.defaultColor(red, green, blue, alpha);
    }

    public static void unsetDefaultColor(VertexConsumer delegate) {
        delegate.unsetDefaultColor();
    }
}
