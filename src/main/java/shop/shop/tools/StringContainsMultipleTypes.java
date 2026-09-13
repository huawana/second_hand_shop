package shop.shop.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringContainsMultipleTypes {
    public static Boolean stringJudgeIfContainTwoType(String password){
        // 定义正则表达式模式
        int typesCount = 0;

        if (password.matches(".*\\d.*")) {
            typesCount++;
        }

        if (password.matches(".*[a-zA-Z].*")) {
            typesCount++;
        }

        if (password.matches(".*\\p{Punct}.*")) {
            typesCount++;
        }

        return typesCount >= 2;
    }
}
