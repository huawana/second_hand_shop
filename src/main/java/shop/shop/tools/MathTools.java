package shop.shop.tools;

public class MathTools {

    //正态函数
    public static double function(double x) {
        return Math.exp(-Math.pow(x / 10, 4));
    }

    // 使用中心差分法计算函数的导数
    public static double derivative(double x, double h) {
        double f_x_plus_h = function(x + h);
        double f_x_minus_h = function(x - h);
        double derivative = (f_x_plus_h - f_x_minus_h) / (2 * h);
        return derivative;
    }
}
