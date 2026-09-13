var deleteButtons = document.getElementsByClassName("delete_product");

// 遍历每个删除按钮，为其添加点击事件监听器
for (var i = 0; i < deleteButtons.length; i++) {
  (function() {
    var button = deleteButtons[i];
    button.addEventListener("click", function() {
      // 获取物品的 ID
      var productId = $(this).data('productid');
      console.log(productId);

      // 保存删除链接所在的行元素
      var row = $(this).closest('tr');

      // 执行 AJAX 请求
      $.ajax({
        url: '/shop/deleteProduct',
        method: 'POST',
        data: { productId: productId },
        success: function(response) {
          // 处理成功响应

          // 从 DOM 中移除该行
          row.remove();

          var popup = document.createElement('div');
          popup.className = 'overlay';

          // 添加弹窗内容
          var modal = document.createElement('div');
          modal.className = 'modal';
          var content = document.createElement('div');
          content.innerHTML = '<p>删除成功</p>';
          modal.appendChild(content);

          popup.appendChild(modal);
          document.body.appendChild(popup);

          // 关闭弹窗
          setTimeout(function() {
            document.body.removeChild(popup);
          }, 1000); // 1秒后自动关闭弹窗
        },
        error: function(error) {
          // 处理错误
        }
      });
    });
  })();
}



// 获取所有的"加入购物车"按钮
var buyButtons = document.getElementsByClassName("buy_product");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < buyButtons.length; i++) {
    buyButtons[i].addEventListener("click", function() {
        event.preventDefault();
        var productContainer = this.parentElement.parentElement;
        var imgPath = productContainer.querySelector("img").getAttribute("src");
        console.log(imgPath);
        // 保存 this 的引用，以在内部函数中使用
        var currentButton = this;

        fetch("/shop/buySuccess", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ imgPath: imgPath })
        })
        .then(function(response) {
            if (response.ok) {
            console.log(666666666);
                window.location.href = "/shop/buySuccess"; // 在响应成功时跳转到指定页面
            }
        });


    });
}


