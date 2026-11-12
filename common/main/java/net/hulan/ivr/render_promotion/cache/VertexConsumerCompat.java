package net.hulan.ivr.render_promotion.cache;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Bridges VertexConsumer's version-specific default-color API. */
public final class VertexConsumerCompat {

    private VertexConsumerCompat() {
    }

    public static void defaultColor(VertexConsumer delegate, int red, int green, int blue, int alpha) {
        VertexConsumerCompatImpl.defaultColor(delegate, red, green, blue, alpha);
    }

    public static void unsetDefaultColor(VertexConsumer delegate) {
        VertexConsumerCompatImpl.unsetDefaultColor(delegate);
    }
}
