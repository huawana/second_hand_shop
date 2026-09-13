package shop.shop.Bean;

public class CartItem {
    private String imgPath;

    public CartItem() {
    }

    public CartItem(String imgPath) {
        this.imgPath = imgPath;
    }

    public String getImgPath() {
        return imgPath;
    }

    public void setImgPath(String imgPath) {
        this.imgPath = imgPath;
    }
}