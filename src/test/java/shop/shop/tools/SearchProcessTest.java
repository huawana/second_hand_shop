package shop.shop.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchProcess} 序列化部分的单元测试。
 *
 * <p>搜索历史被塞进 {@code lxy_user.search} 这一个 text 字段里，格式是
 * {@code 关键词:[权重,已衰减天数,初始权重];关键词2:[...]}。
 * 这个格式没有 schema、没有校验，一旦解析/序列化不对称，搜索历史就会静默损坏，
 * 所以必须用往返测试锁住。
 */
class SearchProcessTest {

    @Test
    @DisplayName("正常格式应解析为字典")
    void stringToDict_normal() {
        HashMap<String, double[]> dict = SearchProcess.stringToDict("球鞋:[3.0,2.0,3.0];耳机:[1.0,0.0,1.0]");

        assertEquals(2, dict.size());
        assertEquals(3.0, dict.get("球鞋")[0], 1e-9);
        assertEquals(2.0, dict.get("球鞋")[1], 1e-9);
        assertEquals(1.0, dict.get("耳机")[2], 1e-9);
    }

    @Test
    @DisplayName("应容忍条目两端空格")
    void stringToDict_shouldTrim() {
        HashMap<String, double[]> dict = SearchProcess.stringToDict(" 球鞋 : [ 3.0 , 2.0 , 3.0 ] ");
        assertEquals(3.0, dict.get("球鞋")[0], 1e-9);
    }

    @Test
    @DisplayName("缺少冒号的分段应抛 IllegalArgumentException（而不是静默丢弃）")
    void stringToDict_invalidPair_throws() {
        assertThrows(IllegalArgumentException.class, () -> SearchProcess.stringToDict("球鞋"));
    }

    @Test
    @DisplayName("值不是数字时应抛 IllegalArgumentException")
    void stringToDict_invalidValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> SearchProcess.stringToDict("球鞋:[abc]"));
    }

    @Test
    @DisplayName("序列化 → 解析 → 再序列化，结果应稳定（幂等）")
    void dictToString_isStable() {
        String source = "球鞋:[3.0,2.0,3.0];耳机:[1.0,0.0,1.0]";

        String once = SearchProcess.dictToString(SearchProcess.stringToDict(source));
        String twice = SearchProcess.dictToString(SearchProcess.stringToDict(once));

        // 第一次序列化的 key 顺序由 HashMap 决定，可能与输入不同；这里验证的是幂等性
        assertEquals(twice, once, "重复序列化必须收敛，否则每天定时任务都会改写搜索历史");

        HashMap<String, double[]> reparsed = SearchProcess.stringToDict(once);
        assertEquals(2, reparsed.size());
        assertEquals(3.0, reparsed.get("球鞋")[0], 1e-9);
    }

    @Test
    @DisplayName("权重衰减语义验证：连续 5 天衰减后，权重应从 3.0 降到 2.x 且天数递增到 5")
    void decaySimulation() {
        double[] value = {3.0, 0.0, 3.0};
        for (int day = 0; day < 5; day++) {
            value[0] += MathTools.derivative(value[1] + 1, 0.001) * value[2];
            value[1] += 1;
        }
        assertTrue(value[0] < 3.0, "衰减后权重必须下降，实际=" + value[0]);
        assertEquals(5.0, value[1], 1e-9);
        // 衰减量应当温和（不是断崖式），否则用户昨天搜的词今天就被淘汰了
        assertTrue(value[0] > 2.5, "5 天内权重不应掉到 2.5 以下，实际=" + value[0]);
    }

    @Test
    @DisplayName("权重低于 0.1 的关键词应被淘汰（这是 DailyUpdateTask 的淘汰阈值）")
    void decay_shouldEventuallyEvict() {
        double[] value = {1.0, 0.0, 1.0};
        int days = 0;
        while (value[0] >= 0.1 && days < 10000) {
            value[0] += MathTools.derivative(value[1] + 1, 0.001) * value[2];
            value[1] += 1;
            days++;
        }
        assertTrue(days < 10000, "关键词应最终被淘汰，否则定时任务永远清理不掉冷门搜索词");
        assertTrue(days > 5, "淘汰不应过快，实际 " + days + " 天");
    }
}
