package shop.shop.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import shop.admin.Bean.Cart;
import shop.admin.mapper.CartMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class StringToList {

    private static CartMapper cartMapper;

    @Autowired
    public StringToList(CartMapper cartMapper) {
        this.cartMapper = cartMapper;
    }


    public static List<Integer> stringToList(String stringList){
        if (stringList == null || stringList.isEmpty()) {
            return new ArrayList<>(); // 返回一个空的整数列表
        }

        String[] strArray = stringList.split(",");
        // 将字符串数组转换为列表
        List<String> list = new ArrayList<>(Arrays.asList(strArray));
        List<Integer> intList = list.stream()
                .mapToInt(Integer::parseInt)
                .boxed()
                .collect(Collectors.toList());

        return intList;
    }

    public static String listToString(List<Integer> intList) {
        // 将整数列表转换为字符串列表
        List<String> stringList = intList.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());

        // 将字符串列表使用逗号连接成一个字符串
        String joinedString = String.join(",", stringList);

        return joinedString;
    }

    public static String cartAddProduct(int id, int productId){
        Cart cart = cartMapper.getCartById(id);
        String products = cart.getProducts();

        List<Integer> numList = stringToList(products);
        if(numList.contains(productId)){
            return products;
        }
        numList.add(productId);
        String newList = listToString(numList);
        return newList;
    }
}