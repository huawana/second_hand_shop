package shop.admin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MD5passEncryption} 单元测试。
 *
 * <p>这里刻意把 MD5 的「已知缺陷」写成测试断言 —— 目的是把问题显性化：
 * 同一个密码永远得到同一个摘要、且长度固定 32 位。Phase 1 迁移到 BCrypt 后，
 * 这两个测试会被替换为「相同密码两次加密结果不同（随机盐）+ 长度 60」。
 */
class MD5passEncryptionTest {

    @Test
    @DisplayName("标准测试向量：\"123456\" 的 MD5 应为 e10adc3949ba59abbe56e057f20f883e")
    void encrypt_knownVector() {
        assertEquals("e10adc3949ba59abbe56e057f20f883e", MD5passEncryption.encrypt("123456"));
    }

    @Test
    @DisplayName("摘要长度固定 32 位十六进制")
    void encrypt_lengthIs32() {
        assertEquals(32, MD5passEncryption.encrypt("any-password").length());
    }

    @Test
    @DisplayName("【已知安全缺陷】相同密码 → 相同摘要（无盐），这正是不该用 MD5 存密码的原因")
    void encrypt_isDeterministic_knownWeakness() {
        assertEquals(MD5passEncryption.encrypt("password123"),
                MD5passEncryption.encrypt("password123"),
                "无盐摘要必然可复现，攻击者可用彩虹表批量反查");
    }

    @Test
    @DisplayName("不同密码应产生不同摘要（无碰撞，基础正确性）")
    void encrypt_differentPasswords() {
        assertNotEquals(MD5passEncryption.encrypt("password123"),
                MD5passEncryption.encrypt("password124"));
    }

    @Test
    @DisplayName("空串应被正常处理而不是抛异常（由上层校验非空）")
    void encrypt_emptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MD5passEncryption.encrypt(""));
    }

    @Test
    @DisplayName("中文密码应能正常编码（依赖 JVM 默认字符集，Phase 1 需显式指定 UTF-8）")
    void encrypt_chinesePassword() {
        // 这里只断言「可调用且长度正确」，不锁定具体值：
        // 因为 getBytes() 用的是平台默认字符集，跨环境结果会变 —— 这本身就是一个待修的隐患
        assertEquals(32, MD5passEncryption.encrypt("中文密码").length());
    }

    @Test
    @DisplayName("工具类不应可被实例化")
    void constructor_isPrivate() {
        assertThrows(IllegalAccessException.class,
                () -> MD5passEncryption.class.getDeclaredConstructor().newInstance());
    }
}
