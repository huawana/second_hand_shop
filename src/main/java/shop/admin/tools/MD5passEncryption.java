package shop.admin.tools;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5passEncryption{

    public static String encrypt(String password){
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
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

        String encryptedPassword = sb.toString();
        return encryptedPassword;
    }
}
