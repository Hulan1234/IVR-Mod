package net.hulan.ksd.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 由若干 {@link KSDPathSegment} 组成的一条有序路径。
 *
 * <p>路径不绑定渲染逻辑，只负责保存几何并提供按采样步长展开后的点列表。
 * 采样结果会被缓存，视图变换或路径内容变化后由持有者重新构建路径对象。</p>
 */
public final class KSDPath {

    /** 路径坐标空间。 */
    public enum CoordinateSpace {
        /** 线路图布局坐标（像素）。 */
        LAYOUT,
        /** 世界坐标（方块）。 */
        WORLD
    }

    private final CoordinateSpace coordinateSpace;
    private final List<KSDPathSegment> segments = new ArrayList<>();
    private List<double[]> cachedPoints;
    private double cachedStep = Double.NaN;
    private double length = -1;

    public KSDPath() {
        this(CoordinateSpace.LAYOUT);
    }

    public KSDPath(CoordinateSpace coordinateSpace) {
        this.coordinateSpace = coordinateSpace == null ? CoordinateSpace.LAYOUT : coordinateSpace;
    }

    public CoordinateSpace getCoordinateSpace() {
        return coordinateSpace;
    }

    /** 追加一段路径，并让长度和采样缓存失效。 */
    public void addSegment(KSDPathSegment segment) {
        if (segment == null) {
            return;
        }
        segments.add(segment);
        cachedPoints = null;
        cachedStep = Double.NaN;
        length = -1;
    }

    public List<KSDPathSegment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** 返回整条路径的近似长度。 */
    public double getLength() {
        if (length >= 0) {
            return length;
        }
        double total = 0;
        for (KSDPathSegment segment : segments) {
            total += segment.getLength();
        }
        length = total;
        return length;
    }

    /**
     * 把路径展开成折线点列表。
     *
     * <p>直线段只输出起止点；贝塞尔段按 {@code step} 对参数均匀采样。
     * 返回值与坐标数组均按只读约定使用，并会在相同 step 下复用缓存。</p>
     *
     * @param step 采样步长，必须大于 0
     * @return {x, z} 点列表
     */
    public List<double[]> getPoints(double step) {
        if (!(step > 0) || Double.isInfinite(step)) {
            throw new IllegalArgumentException("step must be a finite positive number");
        }
        if (cachedPoints != null && cachedStep == step) {
            return cachedPoints;
        }
        if (segments.isEmpty()) {
            cachedPoints = Collections.emptyList();
            cachedStep = step;
            return cachedPoints;
        }
        final List<double[]> points = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            final KSDPathSegment segment = segments.get(i);
            final int intervals;
            if (segment.getType() == KSDPathSegment.Type.LINE) {
                intervals = 1;
            } else {
                intervals = Math.max(1, (int) Math.ceil(segment.getLength() / step));
            }
            // 相邻段共享端点：除第一段外从 1 开始，避免重复点。
            for (int j = i == 0 ? 0 : 1; j <= intervals; j++) {
                points.add(segment.sample(j / (double) intervals));
            }
        }
        cachedPoints = Collections.unmodifiableList(points);
        cachedStep = step;
        return cachedPoints;
    }
}
