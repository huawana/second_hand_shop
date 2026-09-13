package shop.admin.tools;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密码摘要工具。
 *
 * <p>【重要安全提示 —— Phase 1 必须替换】
 * 当前实现有两个严重问题：
 * <ol>
 *   <li><b>无盐（unsalted）</b>：相同密码永远得到相同 MD5，攻击者可用彩虹表批量反查。
 *       库里 30 个用户的密码摘要都是裸 MD5，一旦库泄露等于明文。</li>
 *   <li><b>MD5 本身已被攻破</b>：碰撞攻击成熟，且 GPU 每秒可算百亿次，
 *       暴力破解成本极低。密码存储必须用慢哈希（BCrypt / Argon2 / scrypt）。</li>
 * </ol>
 * Phase 1 会迁移到 Spring Security 的 {@code BCryptPasswordEncoder}（自带随机盐 +
 * 可调 cost 因子），并对存量 MD5 摘要做「登录时透明升级」。
 */
public class MD5passEncryption {

    private MD5passEncryption() {
        // 工具类禁止实例化
    }

    public static String encrypt(String password) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // 【修复】原代码 printStackTrace 后继续执行，此时 md 仍为 null，
            // 下一行会抛 NPE，把「环境不支持 MD5」这个真实原因完全掩盖掉。
            // MD5 是 JDK 规范强制要求的算法，走到这里说明 JVM 环境异常，属于不可恢复错误。
            throw new IllegalStateException("当前 JVM 环境不支持 MD5 算法", e);
        }

        // 将密码转换为字节数组
        byte[] passwordBytes = password.getBytes();

        // 计算MD5摘要
        byte[] digest = md.digest(passwordBytes);

        // 将摘要转换为16进制字符串
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }
}
