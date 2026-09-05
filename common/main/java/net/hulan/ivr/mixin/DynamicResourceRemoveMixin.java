package net.hulan.ivr.mixin;

import mtr.client.ClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/*
 * MTR 动态纹理释放异常抑制 Mixin。
 *
 * 背景：MTR 的列车动态文字屏（如 KCR 南铁的滚动字幕 / 像素文字）在进入渲染范围后，
 * 每帧通过 ClientCache.getPixelatedText -> getResource 生成/刷新动态纹理。
 * 当同一纹理 key 被并发刷新（Netty IO 线程与渲染线程）或已被释放时，
 * DynamicResource.remove() 的 textureManager.release / getTexture().releaseId() / close()
 * 会抛 FileNotFoundException，并在渲染线程打印约 45 行堆栈，
 * 导致列车进入渲染区域后持续掉帧。
 *
 * 做法：用 @Overwrite 重写 DynamicResource.remove()，完整保留原释放逻辑，
 * 但用 try-catch 吞掉释放异常。纹理生命周期不受影响（释放仍会执行），
 * 只是不再向渲染线程抛堆栈。
 *
 * 注：5 个支持版本（1.16.5 ~ 1.19.4）的 remove() 字节码完全一致，
 * 此实现经 loom 编译期自动 remap 到各版本的运行时方法名。
 */
@Mixin(ClientCache.DynamicResource.class)
public abstract class DynamicResourceRemoveMixin {

    @Shadow(remap = false)
    @Final
    public ResourceLocation resourceLocation;

    /*
     * 重写 DynamicResource.remove()：释放纹理但吞掉并发释放导致的异常。
     * 与 MTR 原版逻辑一致：跳过默认资源（黑/白/透明），否则
     * release(texture) + getTexture().releaseId() + close()。
     * 默认资源判断通过 resourceLocation 的命名空间/路径前缀判断（避免访问外层 private 字段）。
     */
    /**
     * @author
     */
    @Overwrite(remap = false)
    private void remove() {
        try {
            String path = resourceLocation.getPath();
            if (resourceLocation.getNamespace().equals("mtr") && path.startsWith("textures/block/black")
                    || resourceLocation.getNamespace().equals("mtr") && path.startsWith("textures/block/white")
                    || resourceLocation.getNamespace().equals("mtr") && path.startsWith("textures/block/transparent")) {
                return;
            }
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            textureManager.release(resourceLocation);
            AbstractTexture abstractTexture = textureManager.getTexture(resourceLocation);
            if (abstractTexture != null) {
                abstractTexture.releaseId();
                abstractTexture.close();
            }
        } catch (Exception ignored) {
        }
    }
}
