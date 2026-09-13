package shop.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import shop.admin.tools.MD5passEncryption;

/**
 * 密码校验与编码 —— 含「存量 MD5 透明升级」。
 *
 * <p>现实问题：库里已有 30 个用户 + 1 个管理员的密码是<b>无盐 MD5</b>。
 * 直接切换到 BCrypt 会让所有人无法登录。两种应对方式：
 * <ul>
 *   <li>写一个一次性迁移脚本把所有 MD5 转成 BCrypt —— 做不到：
 *       MD5 是单向的，脚本无法反推出明文，除非知道每个人的原始密码。</li>
 *   <li><b>登录时透明升级</b>（本项目采用）：用户下次登录、密码校验通过的那一刻，
 *       顺手把库里的 MD5 换成 BCrypt。用户无感知、不需要重置密码，
 *       随着时间推移存量摘要自然收敛为 0。</li>
 * </ul>
 *
 * <p>面试可讲的点：这本质是「灰度迁移」思路 —— 不追求一次性切换，
 * 而是让新旧两种格式在过渡期内<b>共存</b>，靠真实流量逐步收敛。
 * 代价是过渡期代码里必须保留对旧格式的兼容分支，且要记得在收敛后删除。
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** BCrypt 摘要固定以 $2a$ / $2b$ / $2y$ 开头；MD5 十六进制摘要是纯 32 位 hex */
    private static final String BCRYPT_PREFIX = "$2";

    private final PasswordEncoder passwordEncoder;

    public PasswordService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /** 用 BCrypt 编码明文（注册 / 改密码时使用）。 */
    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验密码，兼容两种存储格式。
     *
     * @param rawPassword    用户提交的明文
     * @param storedPassword 库中存的摘要（可能是 MD5，也可能是 BCrypt）
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        if (isLegacyMd5(storedPassword)) {
            // 过渡期兼容分支：用旧的 MD5 规则校验
            return MD5passEncryption.encrypt(rawPassword).equalsIgnoreCase(storedPassword);
        }
        return passwordEncoder.matches(rawPassword, storedPassword);
    }

    /** @return true 表示库中存的还是旧的无盐 MD5，登录成功后应升级 */
    public boolean isLegacyMd5(String storedPassword) {
        return storedPassword != null && !storedPassword.startsWith(BCRYPT_PREFIX);
    }

    /** 记录一次透明升级（调用方负责把新摘要写回数据库）。 */
    public void logUpgrade(String username) {
        log.info("密码摘要透明升级：用户[{}] MD5 → BCrypt", username);
    }
}
