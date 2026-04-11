/**
 * مطراش — واجهة المحادثة
 * يتعامل مع WebSocket وعرض الرسائل والمستخدمين المتصلين.
 */

(function () {
  const username = sessionStorage.getItem('mitrash_username');
  if (!username) {
    window.location.href = '/';
    return;
  }

  // عناصر DOM
  const statusIndicator = document.getElementById('status-indicator');
  const statusText = document.getElementById('status-text');
  const currentUserEl = document.getElementById('current-user');
  const currentUserAvatar = document.getElementById('current-user-avatar');
  const onlineUsersList = document.getElementById('online-users-list');
  const historyUsersList = document.getElementById('history-users-list');
  const onlineCount = document.getElementById('online-count');
  const messagesContainer = document.getElementById('messages-container');
  const chatTitle = document.getElementById('chat-title');
  const selectedUserAvatar = document.getElementById('selected-user-avatar');
  const selectedUserStatus = document.getElementById('selected-user-status');
  const chatInputForm = document.getElementById('chat-input-form');
  const messageInput = document.getElementById('message-input');
  const sendBtn = document.getElementById('send-btn');
  const logoutBtn = document.getElementById('logout-btn');

  // الحالة
  let ws = null;
  let selectedUser = null;
  let allMessages = [];
  let onlineUsers = [];
  let reconnectTimer = null;
  let reconnectAttempts = 0;

  // ضبط معلومات المستخدم الحالي
  currentUserEl.textContent = username;
  currentUserAvatar.textContent = username.charAt(0).toUpperCase();

  // --- WebSocket ---
  function connect() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}`;

    setStatus('connecting', 'جاري الاتصال...');
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      reconnectAttempts = 0;
      ws.send(JSON.stringify({ type: 'login', username }));
      setStatus('online', 'متصل');
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        handleMessage(data);
      } catch (err) {
        console.error('فشل تحليل الرسالة:', err);
      }
    };

    ws.onclose = () => {
      setStatus('offline', 'غير متصل');
      scheduleReconnect();
    };

    ws.onerror = () => {
      setStatus('offline', 'خطأ في الاتصال');
    };
  }

  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);
    setStatus('connecting', `إعادة المحاولة خلال ${Math.round(delay / 1000)} ث`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function setStatus(state, text) {
    statusIndicator.className = 'status-indicator ' + state;
    statusText.textContent = text;
  }

  // --- معالجة الرسائل الواردة ---
  function handleMessage(data) {
    switch (data.type) {
      case 'welcome':
        console.log('[ws]', data.message);
        break;

      case 'online_users':
        onlineUsers = (data.users || []).filter((u) => u !== username);
        renderOnlineUsers();
        updateSelectedUserStatus();
        break;

      case 'history':
        allMessages = data.messages || [];
        renderHistoryUsers();
        renderMessages();
        break;

      case 'message':
        allMessages.push(data);
        renderHistoryUsers();
        // إذا كانت الرسالة من/إلى المستخدم المحدد حالياً، حدّث العرض
        if (
          selectedUser &&
          (data.sender === selectedUser || data.recipient === selectedUser)
        ) {
          appendMessage(data);
          scrollToBottom();
        } else if (data.sender !== username) {
          // إشعار بسيط للرسائل الجديدة
          notifyNewMessage(data);
        }
        break;

      case 'error':
        console.error('[server]', data.message);
        alert(data.message);
        break;

      default:
        console.warn('نوع رسالة غير معروف:', data.type);
    }
  }

  function notifyNewMessage(msg) {
    // تمييز المستخدم في قائمة المحادثات
    const item = document.querySelector(
      `[data-user="${escapeAttr(msg.sender)}"]`
    );
    if (item) {
      item.classList.add('has-new');
    }
  }

  // --- عرض قائمة المتصلين ---
  function renderOnlineUsers() {
    onlineCount.textContent = onlineUsers.length;

    if (onlineUsers.length === 0) {
      onlineUsersList.innerHTML =
        '<p class="empty-sidebar">لا يوجد مستخدمون متصلون</p>';
      return;
    }

    onlineUsersList.innerHTML = '';
    onlineUsers.forEach((user) => {
      const item = createUserItem(user, true);
      onlineUsersList.appendChild(item);
    });
  }

  // --- عرض قائمة المحادثات السابقة ---
  function renderHistoryUsers() {
    const chatPartners = new Set();
    allMessages.forEach((m) => {
      if (m.sender === username) chatPartners.add(m.recipient);
      else if (m.recipient === username) chatPartners.add(m.sender);
    });

    // استبعد المتصلين الآن (يظهرون في القسم الآخر)
    const historyOnly = Array.from(chatPartners).filter(
      (u) => !onlineUsers.includes(u)
    );

    if (historyOnly.length === 0) {
      historyUsersList.innerHTML = '';
      return;
    }

    historyUsersList.innerHTML = '';
    historyOnly.forEach((user) => {
      const item = createUserItem(user, false);
      historyUsersList.appendChild(item);
    });
  }

  function createUserItem(user, isOnline) {
    const div = document.createElement('div');
    div.className = 'user-item' + (selectedUser === user ? ' active' : '');
    div.setAttribute('data-user', user);
    div.innerHTML = `
      <div class="avatar-small">${escapeHtml(user.charAt(0).toUpperCase())}</div>
      <div class="user-meta">
        <div class="user-name">${escapeHtml(user)}</div>
        <div class="user-status">${isOnline ? 'متصل الآن' : 'غير متصل'}</div>
      </div>
      ${isOnline ? '<div class="status-dot online"></div>' : ''}
    `;
    div.addEventListener('click', () => selectUser(user));
    return div;
  }

  // --- اختيار مستخدم ---
  function selectUser(user) {
    selectedUser = user;
    chatTitle.textContent = user;
    selectedUserAvatar.textContent = user.charAt(0).toUpperCase();
    messageInput.disabled = false;
    sendBtn.disabled = false;
    messageInput.focus();

    // إزالة تنبيه الرسائل الجديدة
    const item = document.querySelector(`[data-user="${escapeAttr(user)}"]`);
    if (item) item.classList.remove('has-new');

    updateSelectedUserStatus();
    renderOnlineUsers();
    renderHistoryUsers();
    renderMessages();
  }

  function updateSelectedUserStatus() {
    if (!selectedUser) {
      selectedUserStatus.textContent = '';
      return;
    }
    const isOnline = onlineUsers.includes(selectedUser);
    selectedUserStatus.textContent = isOnline ? 'متصل الآن' : 'غير متصل';
    selectedUserStatus.className =
      'selected-user-status' + (isOnline ? ' online' : '');
  }

  // --- عرض الرسائل ---
  function renderMessages() {
    if (!selectedUser) {
      messagesContainer.innerHTML = `
        <div class="empty-state">
          <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          <h3>ابدأ محادثة جديدة</h3>
          <p>اختر مستخدماً من القائمة الجانبية لبدء المحادثة</p>
        </div>
      `;
      return;
    }

    const relevant = allMessages.filter(
      (m) =>
        (m.sender === username && m.recipient === selectedUser) ||
        (m.sender === selectedUser && m.recipient === username)
    );

    messagesContainer.innerHTML = '';

    if (relevant.length === 0) {
      messagesContainer.innerHTML = `
        <div class="empty-state">
          <h3>لا توجد رسائل بعد</h3>
          <p>ابدأ المحادثة بإرسال أول رسالة</p>
        </div>
      `;
      return;
    }

    let lastDate = null;
    relevant.forEach((msg) => {
      const msgDate = new Date(msg.timestamp);
      const dateStr = msgDate.toLocaleDateString('ar-AE', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
      });
      if (dateStr !== lastDate) {
        const divider = document.createElement('div');
        divider.className = 'date-divider';
        divider.textContent = dateStr;
        messagesContainer.appendChild(divider);
        lastDate = dateStr;
      }
      appendMessage(msg);
    });

    scrollToBottom();
  }

  function appendMessage(msg) {
    const isSent = msg.sender === username;
    const div = document.createElement('div');
    div.className = 'message ' + (isSent ? 'sent' : 'received');
    const time = new Date(msg.timestamp).toLocaleTimeString('ar-AE', {
      hour: '2-digit',
      minute: '2-digit',
    });
    div.innerHTML = `
      <div class="message-text">${escapeHtml(msg.text)}</div>
      <div class="message-meta">${time}</div>
    `;
    messagesContainer.appendChild(div);
  }

  function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
  }

  // --- إرسال الرسائل ---
  chatInputForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = messageInput.value.trim();
    if (!text || !selectedUser) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      alert('غير متصل بالخادم. يرجى الانتظار...');
      return;
    }

    ws.send(
      JSON.stringify({
        type: 'message',
        to: selectedUser,
        text,
      })
    );

    messageInput.value = '';
    messageInput.focus();
  });

  // --- تسجيل الخروج ---
  logoutBtn.addEventListener('click', () => {
    if (confirm('هل تريد تسجيل الخروج؟')) {
      sessionStorage.removeItem('mitrash_username');
      if (ws) ws.close();
      window.location.href = '/';
    }
  });

  // --- دوال مساعدة ---
  function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = String(text);
    return div.innerHTML;
  }

  function escapeAttr(text) {
    return String(text).replace(/"/g, '&quot;');
  }

  // ابدأ الاتصال
  connect();
})();
