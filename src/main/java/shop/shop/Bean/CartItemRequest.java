package shop.shop.Bean;

import jakarta.validation.constraints.NotBlank;

/**
 * 前端 ajax 请求体。
 *
 * <p>注意：本项目用「图片路径」作为商品的业务标识（而不是 id），原因是前端页面上
 * 唯一可靠的商品标识就是 img 的 src。这是原始设计的一个缺陷（id 才是主键），
 * Phase 2 会改为传 productId。
 */
public class CartItemRequest {

    // 【新增】参数校验：imgPath 是必填项，缺失时由 GlobalExceptionHandler 统一转成 400 响应
    @NotBlank(message = "商品标识(imgPath)不能为空")
    private String imgPath;

    public CartItemRequest() {
    }

    public CartItemRequest(String imgPath) {
        this.imgPath = imgPath;
    }

    public String getImgPath() {
        return imgPath;
    }

    public void setImgPath(String imgPath) {
        this.imgPath = imgPath;
    }
}
