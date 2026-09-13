package shop.shop.tools;

import shop.admin.Bean.Product;

import java.util.Comparator;

public class ProductsSortedByTime implements Comparator<Product>{
    @Override
    public int compare(Product product1, Product product2) {
        return product2.getCreatedAt().compareTo(product1.getCreatedAt());
    }
}
