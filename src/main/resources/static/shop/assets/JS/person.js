var deleteButtons = document.getElementsByClassName("deleteMyRelease");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < deleteButtons.length; i++) {
    deleteButtons[i].addEventListener("click", function(event) {
        event.preventDefault();
        var productContainer = this.parentElement.parentElement;
        var imgPath = productContainer.querySelector("img").getAttribute("src");

        // 保存 this 的引用，以在内部函数中使用
        var currentButton = this;

        fetch("/shop/deleteMyRelease", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ imgPath: imgPath })
        })
        .then(function(response) {
            if (response.ok) {
                // 处理响应结果...
                var row = currentButton.parentNode.parentNode;
                // 从 DOM 中移除该行
                row.remove();
                // 创建弹窗
                var popup = document.createElement('div');
                popup.className = 'overlay';

                var modal = document.createElement('div');
                modal.className = 'modal';

                // 添加弹窗内容
                var content = document.createElement('div');
                content.innerHTML = '<p>删除成功</p>';
                modal.appendChild(content);

                popup.appendChild(modal);
                document.body.appendChild(popup);

                // 关闭弹窗
                setTimeout(function() {
                    document.body.removeChild(popup);
                }, 1000); // 1秒后自动关闭弹窗
            } else {
                throw new Error("请求失败");
            }
        })
        .catch(function(error) {
            // 处理错误...
        });
    });
}



function changeSellerStatus(event) {
     event.preventDefault();

         // 通过事件的目标元素（即被点击的链接）来获取其所在的行元素
     var row = event.target.closest('tr');

     // 在行元素中查找img元素，并获取其src属性值
     var imgPath = row.querySelector('img').getAttribute('src');
     var status = row.querySelector('.status')
     var handle = row.querySelector('.handle')
     var statusContent = status.textContent;
     var handleContent = handle.textContent;
     if(status==="无"){
        return;
     }
      // 发送Ajax请求
      fetch("/shop/changeStatus", {
          method: "POST",
          headers: {
              "Content-Type": "application/json"
          },
          body: JSON.stringify({
              imgPath: imgPath,
          })
      })
      .then(function(response) {

            // 从 DOM 中移除该行
            if(statusContent === "等待发货"){
                console.log(1212121212)
                status.textContent = "已发货";
                handle.textContent = "无";
            }else if(statusContent === "已发货"){
                status.textContent = "订单已完成";
                handle.textContent = "无";
            }
              // 处理响应结果...
            // 创建弹窗
            var popup = document.createElement('div');
            popup.className = 'overlay';

            var modal = document.createElement('div');
            modal.className = 'modal';

            // 添加弹窗内容
            var content = document.createElement('div');
            content.innerHTML = '<p>订单进度更新成功</p>';
            modal.appendChild(content);

            popup.appendChild(modal);
            document.body.appendChild(popup);

            // 关闭弹窗
            setTimeout(function() {
                document.body.removeChild(popup);
            }, 1000); // 1秒后自动关闭弹窗
        })
        .catch(function(error) {
            // 处理错误...
        });

    // 使用 productId 和 productStatus 进行进一步的操作
    // ...
}


function changeBuyerStatus(event) {
     event.preventDefault();

         // 通过事件的目标元素（即被点击的链接）来获取其所在的行元素
     var row = event.target.closest('tr');

     // 在行元素中查找img元素，并获取其src属性值
     var imgPath = row.querySelector('img').getAttribute('src');
     var status = row.querySelector('.status')
     var handle = row.querySelector('.handle')
     var statusContent = status.textContent;
     var handleContent = handle.textContent;
     if(handleContent==="无"){
        return;
     }
      // 发送Ajax请求
      fetch("/shop/changeStatus", {
          method: "POST",
          headers: {
              "Content-Type": "application/json"
          },
          body: JSON.stringify({
              imgPath: imgPath,
          })
      })
      .then(function(response) {

            // 从 DOM 中移除该行
            if(statusContent === "已发货"){
                status.textContent = "订单已完成";
                handle.textContent = "无";
            }
              // 处理响应结果...
            // 创建弹窗
            var popup = document.createElement('div');
            popup.className = 'overlay';

            var modal = document.createElement('div');
            modal.className = 'modal';

            // 添加弹窗内容
            var content = document.createElement('div');
            content.innerHTML = '<p>订单进度更新成功</p>';
            modal.appendChild(content);

            popup.appendChild(modal);
            document.body.appendChild(popup);

            // 关闭弹窗
            setTimeout(function() {
                document.body.removeChild(popup);
            }, 1000); // 1秒后自动关闭弹窗
        })
        .catch(function(error) {
            // 处理错误...
        });

    // 使用 productId 和 productStatus 进行进一步的操作
    // ...
}
//function changeStatus() {
//    // 通过productId获取对应的状态元素
//    console.log(66666);
//
//    // 在这里可以根据原状态进行相应的处理
//
//    // 修改状态信息和操作信息
//
//    // 其他操作信息的修改
//
//    // 可以在这里通过Ajax请求将状态信息和操作信息发送给后端进行更新
//
//    // 阻止链接的默认行为，避免页面跳转
//    event.preventDefault();
//  }