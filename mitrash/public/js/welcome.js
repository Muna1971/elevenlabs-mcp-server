/**
 * مطراش — صفحة الترحيب
 * يتعامل مع تسجيل الدخول وحفظ اسم المستخدم.
 */

(function () {
  const form = document.getElementById('login-form');
  const input = document.getElementById('username');
  const errorMsg = document.getElementById('error-msg');

  // إذا كان المستخدم مسجلاً بالفعل، انقله للمحادثة
  const existing = sessionStorage.getItem('mitrash_username');
  if (existing) {
    window.location.href = '/chat.html';
    return;
  }

  function showError(message) {
    errorMsg.textContent = message;
    errorMsg.classList.add('visible');
    setTimeout(() => errorMsg.classList.remove('visible'), 3000);
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const username = input.value.trim();

    if (!username) {
      showError('الرجاء إدخال اسم المستخدم');
      return;
    }

    if (username.length < 2) {
      showError('اسم المستخدم يجب أن يكون حرفين على الأقل');
      return;
    }

    if (username.length > 32) {
      showError('اسم المستخدم طويل جداً (الحد الأقصى 32 حرفاً)');
      return;
    }

    // تحقق من وجود أحرف صالحة فقط
    if (!/^[\u0600-\u06FFa-zA-Z0-9_\s-]+$/.test(username)) {
      showError('اسم المستخدم يحتوي على أحرف غير صالحة');
      return;
    }

    sessionStorage.setItem('mitrash_username', username);
    window.location.href = '/chat.html';
  });
})();
