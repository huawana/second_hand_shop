// 获取所有的"加入购物车"按钮
var addToCartButtons = document.getElementsByClassName("add_to_cart");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < addToCartButtons.length; i++) {
    addToCartButtons[i].addEventListener("click", function() {
        fetch('/shop/checkSession', {
            method: 'GET',
            headers: {
              'Content-Type': 'application/json'
            },
        })

        .then(response => response.json())
          .then(result => {
            console.log(result)
            // 在收到Spring Boot函数的返回结果后执行下一步操作
            if (result === true) {
              // 继续执行JavaScript函数
              var productContainer = this.parentElement;
              var imgPath = productContainer.querySelector("img").getAttribute("src");
              console.log(imgPath)
              var currentButton = this;
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
                console.log(555555555)
                    var row = currentButton.parentNode;
                  // 从 DOM 中移除该行
                  row.remove();
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
                    window.location.href = "redirect:/shop/index";

              })
              .catch(function(error) {
                  // 处理错误...
              });
            } else {
              // 处理返回结果为false的情况
              fetch('/shop/login')
                .then(response => {
                  // 处理响应
                  window.location.href = "/shop/login";
                })
                .catch(error => {
                  // 处理错误
                });
            }
          })
          .catch(error => {
            // 处理请求发送或返回结果处理过程中的错误
          });
    });
}


