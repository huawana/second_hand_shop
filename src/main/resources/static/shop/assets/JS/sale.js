document.addEventListener("DOMContentLoaded", function() {
    const imageInput = document.getElementById("image");
    const previewImage = document.getElementById("preview");

    imageInput.addEventListener("change", function() {
      const file = imageInput.files[0];
      const reader = new FileReader();

      reader.onload = function(e) {
        previewImage.src = e.target.result;
      }

      if (file) {
        reader.readAsDataURL(file);
      } else {
        previewImage.src = "";
      }
    });
});