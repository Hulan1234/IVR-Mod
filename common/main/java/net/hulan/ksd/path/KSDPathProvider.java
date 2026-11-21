package net.hulan.ksd.path;

import java.util.List;

/**
 * 可复用的路径提供者接口。
 *
 * <p>线路图生成器实现该接口后，渲染层只依赖路径模型，不再关心站点布局与曲线生成细节。</p>
 */
public interface KSDPathProvider {

    /** 返回当前所有可绘制路径。 */
    List<KSDPath> getPaths();

    /** 返回指定线路对应的路径；不存在或该线路没有可绘制段时返回 {@code null}。 */
    KSDPath getPath(long routeId);

    /** 丢弃内部缓存，下一次读取时按需重建。 */
    void invalidate();
}
