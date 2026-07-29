package net.hulan.ksd.data;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.opengl.GL11;

public class Utils_1_16_5 extends Utils {

    public void beginDrawingCircle(BufferBuilder buffer) {
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
    }
}
