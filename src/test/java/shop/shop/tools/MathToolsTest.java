package shop.shop.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MathTools} 单元测试。
 *
 * <p>这是项目自研「搜索词权重衰减」算法的数学基础，之前完全没有测试覆盖：
 * 权重方向搞反（越久越重要）这种 bug 在界面上是看不出来的，只能靠单测锁住。
 */
class MathToolsTest {

    @Test
    @DisplayName("f(x)=e^(-(x/10)^4)：x=0 时取最大值 1")
    void function_atZero_isOne() {
        assertEquals(1.0, MathTools.function(0), 1e-12);
    }

    @Test
    @DisplayName("f(10) = e^-1 ≈ 0.3679（10 天是权重的半衰点附近）")
    void function_atTen() {
        assertEquals(Math.exp(-1), MathTools.function(10), 1e-12);
    }

    @Test
    @DisplayName("x 越大，f(x) 单调递减（且极快收敛到 0）")
    void function_isMonotonicallyDecreasing() {
        assertTrue(MathTools.function(1) > MathTools.function(5));
        assertTrue(MathTools.function(5) > MathTools.function(10));
        assertTrue(MathTools.function(10) > MathTools.function(20));
    }

    @Test
    @DisplayName("中心差分导数在 x=0 处为 0（偶函数，对称）")
    void derivative_atZero_isZero() {
        assertEquals(0.0, MathTools.derivative(0, 0.001), 1e-9);
    }

    @Test
    @DisplayName("x>0 时导数为负 —— 这是权重衰减方向的依据")
    void derivative_isNegativeForPositiveX() {
        assertTrue(MathTools.derivative(1, 0.001) < 0, "x>0 时导数必须为负，否则权重会随时间递增");
        assertTrue(MathTools.derivative(5, 0.001) < 0);
    }

    @Test
    @DisplayName("衰减幅度随天数收敛：第 20 天的日衰减量远小于第 5 天")
    void derivative_convergesAsDaysGrow() {
        double day5 = Math.abs(MathTools.derivative(5, 0.001));
        double day20 = Math.abs(MathTools.derivative(20, 0.001));
        assertTrue(day20 < day5,
                "天数越大衰减量应越小（长尾保留），实际 day5=" + day5 + " day20=" + day20);
    }
}
