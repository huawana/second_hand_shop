// 获取所有的"加入购物车"按钮
var addToCartButtons = document.getElementsByClassName("add_to_cart");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < addToCartButtons.length; i++) {
    addToCartButtons[i].addEventListener("click", function(event) {
        event.preventDefault(); // 阻止默认的链接点击行为

        var productContainer = this.closest(".container");
        var imgPath = productContainer.querySelector(".left img").getAttribute("src");

        // 发送Ajax请求
        fetch("/shop/addToCart", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                imgPath: imgPath
            })
        })
        .then(function(response) {
            // 处理响应结果...

            // 创建弹窗
            var popup = document.createElement('div');
            popup.className = 'overlay';

            var modal = document.createElement('div');
            modal.className = 'modal';

            // 添加弹窗内容
            var content = document.createElement('div');
            content.innerHTML = '<p>已加入购物车</p>';
            modal.appendChild(content);

            popup.appendChild(modal);
            document.body.appendChild(popup);

            // 关闭弹窗
            setTimeout(function() {
                document.body.removeChild(popup);
            }, 1000); // 1秒后自动关闭弹窗
            window.location.href = "/shop/index";
        })
        .catch(function(error) {
            // 处理错误...
        });
    });
}