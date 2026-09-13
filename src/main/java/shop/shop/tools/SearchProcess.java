package shop.shop.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import shop.admin.Bean.Product;
import shop.admin.Bean.User;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;

import java.util.*;
@Component
public class SearchProcess {

    private static ProductMapper productMapper;
    private static UserMapper userMapper;

    @Autowired
    public SearchProcess(ProductMapper productMapper,UserMapper userMapper) {
        SearchProcess.productMapper = productMapper;
        SearchProcess.userMapper = userMapper;
    }
    public static HashMap<String, double[]> stringToDict(String text) {
        HashMap<String, double[]> dictionary = new HashMap<>();

        // 去除字符串两端的空格
        text = text.trim();

        // 按分号分割键值对
        String[] pairs = text.split(";");

        for (String pair : pairs) {
            // 按冒号分割键和值
            String[] keyValue = pair.split(":");
            if (keyValue.length != 2) {
                throw new IllegalArgumentException("Invalid key-value pair: " + pair);
            }

            // 去除键和值两端的空格
            String key = keyValue[0].trim();
            String valueString = keyValue[1].trim();

            // 去除值两端的括号
            valueString = valueString.replaceAll("\\[|\\]", "");

            // 分割值中的数字
            String[] values = valueString.split(",");
            double[] doubleValues = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                try {
                    doubleValues[i] = Double.parseDouble(values[i].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid value: " + values[i]);
                }
            }

            // 将键值对添加到字典
            dictionary.put(key, doubleValues);
        }

        return dictionary;
    }

    public static String dictToString(HashMap<String, double[]> dictionary) {
        StringBuilder stringBuilder = new StringBuilder();

        for (String key : dictionary.keySet()) {
            stringBuilder.append(key).append(":[");
            double[] values = dictionary.get(key);
            for (int i = 0; i < values.length; i++) {
                stringBuilder.append(values[i]);
                if (i < values.length - 1) {
                    stringBuilder.append(",");
                }
            }
            stringBuilder.append("];");
        }

        // 删除最后一个分号
        if (stringBuilder.length() > 0) {
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        }

        return stringBuilder.toString();
    }

    public static List<Product> getProductListBySearchHistory(String username,String search) {
        int number = 100;
        User user = userMapper.getUserByUsername(username);
        int uid = user.getId();
        Map<String, double[]> dict = stringToDict(search);
        List<Map.Entry<String, double[]>> sortedList = new ArrayList<>(dict.entrySet());
        Collections.sort(sortedList, new Comparator<Map.Entry<String, double[]>>() {
            public int compare(Map.Entry<String, double[]> entry1, Map.Entry<String, double[]> entry2) {
                double value1 = entry1.getValue()[0];
                double value2 = entry2.getValue()[0];
                return -Double.compare(value1, value2);
            }
        });
        List<Product> productList = new ArrayList<>();
        List<String> keyList = new ArrayList<>();
        List<Double> valueList = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : sortedList) {
            String key = entry.getKey();
            double[] value = entry.getValue();
            keyList.add(key);
            valueList.add(value[0]);
        }
        int maxValue = 0;
        for (int i = 0; i < keyList.size(); i++) {
            maxValue += valueList.get(i);
        }
        int nowValue = 0;
        int thisProductNumber;
        List<Integer> cartList = UserProcess.getCartList(uid);
        for (int i = 0; i < keyList.size(); i++) {
            thisProductNumber = (int) (number * (valueList.get(i) / maxValue));
            List<Product> productSearchList = productMapper.getProductBySearch(username,"%" + keyList.get(i) + "%");

            UserProcess.removeCartElementFromProducts(productSearchList,cartList);
            if (productSearchList.size() < thisProductNumber) {
                productList.addAll(productSearchList);
                nowValue += productSearchList.size();
            } else {
                productList.addAll(productSearchList.subList(0, thisProductNumber));
                nowValue += thisProductNumber;
            }
        }

        List<Product> productAllList = productMapper.getProducts(username);
        UserProcess.removeCartElementFromProducts(productAllList,cartList);
        Random random = new Random();
        while (number-nowValue > 0) {
            int index = random.nextInt(productAllList.size());
            Product product = productAllList.get(index);
            if (!productList.contains(product)) {
                productList.add(product);
                number -= 1;
            }
        }

        productList.sort(new Comparator<Product>() {
            @Override
            public int compare(Product product1, Product product2) {
                return -Double.compare(product1.getViewCount(), product2.getViewCount());
            }
        });
        return productList;
    }
}
