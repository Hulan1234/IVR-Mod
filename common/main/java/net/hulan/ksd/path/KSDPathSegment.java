package net.hulan.ksd.path;

/**
 * 线路图中的一段二维路径（只使用 X/Z 平面坐标）。
 *
 * <p>直线段只需要起止点；三次贝塞尔段需要起点、两个控制点和终点。
 * 客户端所有布局相关代码只通过 {@link #sample(double)} 和 {@link #getLength()}
 * 消费路径，因此不依赖任何游戏类。</p>
 */
public final class KSDPathSegment {

    /** 路径段类型。 */
    public enum Type {
        LINE,
        CUBIC_BEZIER
    }

    /** 贝塞尔长度的固定采样步数，数值越大越精确。 */
    private static final int BEZIER_LENGTH_SAMPLES = 24;

    private final Type type;
    private final double startX;
    private final double startZ;
    private final double control1X;
    private final double control1Z;
    private final double control2X;
    private final double control2Z;
    private final double endX;
    private final double endZ;
    private double length = -1;

    private KSDPathSegment(Type type,
                           double startX, double startZ,
                           double control1X, double control1Z,
                           double control2X, double control2Z,
                           double endX, double endZ) {
        this.type = type;
        this.startX = startX;
        this.startZ = startZ;
        this.control1X = control1X;
        this.control1Z = control1Z;
        this.control2X = control2X;
        this.control2Z = control2Z;
        this.endX = endX;
        this.endZ = endZ;
    }

    /** 创建一条直线段。 */
    public static KSDPathSegment line(double startX, double startZ, double endX, double endZ) {
        return new KSDPathSegment(Type.LINE,
                startX, startZ,
                startX, startZ,
                endX, endZ,
                endX, endZ);
    }

    /** 创建一条三次贝塞尔曲线段。 */
    public static KSDPathSegment bezier(double startX, double startZ,
                                        double control1X, double control1Z,
                                        double control2X, double control2Z,
                                        double endX, double endZ) {
        return new KSDPathSegment(Type.CUBIC_BEZIER,
                startX, startZ,
                control1X, control1Z,
                control2X, control2Z,
                endX, endZ);
    }

    public Type getType() {
        return type;
    }

    public double getStartX() {
        return startX;
    }

    public double getStartZ() {
        return startZ;
    }

    public double getControl1X() {
        return control1X;
    }

    public double getControl1Z() {
        return control1Z;
    }

    public double getControl2X() {
        return control2X;
    }

    public double getControl2Z() {
        return control2Z;
    }

    public double getEndX() {
        return endX;
    }

    public double getEndZ() {
        return endZ;
    }

    /**
     * 按参数 t 采样路径段上的点。
     *
     * @param t 0..1 之间的参数，越界时会被夹到端点
     * @return 长度为 2 的数组 {x, z}
     */
    public double[] sample(double t) {
        final double clamped = t < 0 ? 0 : Math.min(t, 1);
        if (type == Type.LINE) {
            return new double[]{
                    startX + (endX - startX) * clamped,
                    startZ + (endZ - startZ) * clamped
            };
        }
        final double oneMinusT = 1 - clamped;
        final double b0 = oneMinusT * oneMinusT * oneMinusT;
        final double b1 = 3 * oneMinusT * oneMinusT * clamped;
        final double b2 = 3 * oneMinusT * clamped * clamped;
        final double b3 = clamped * clamped * clamped;
        return new double[]{
                b0 * startX + b1 * control1X + b2 * control2X + b3 * endX,
                b0 * startZ + b1 * control1Z + b2 * control2Z + b3 * endZ
        };
    }

    /** 返回路径段长度；贝塞尔长度使用固定步长采样近似，首次调用后缓存。 */
    public double getLength() {
        if (length >= 0) {
            return length;
        }
        if (type == Type.LINE) {
            length = Math.hypot(endX - startX, endZ - startZ);
            return length;
        }
        double total = 0;
        double[] previous = sample(0);
        for (int i = 1; i <= BEZIER_LENGTH_SAMPLES; i++) {
            double[] current = sample(i / (double) BEZIER_LENGTH_SAMPLES);
            total += Math.hypot(current[0] - previous[0], current[1] - previous[1]);
            previous = current;
        }
        length = total;
        return length;
    }
}
