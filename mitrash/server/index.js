/**
 * مطراش — الخادم الرئيسي
 * Express لتقديم الملفات الثابتة + WebSocket للمراسلة الفورية.
 */

const express = require('express');
const http = require('http');
const path = require('path');
const { WebSocketServer } = require('ws');

const {
  initDB,
  registerUser,
  getAllUsers,
  saveMessage,
  getMessagesForUser,
} = require('./db');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

const PUBLIC_DIR = path.join(__dirname, '..', 'public');

app.use(express.json());
app.use(express.static(PUBLIC_DIR));

// نقطة نهاية للتحقق من الصحة
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', app: 'مطراش', version: '0.1.0' });
});

// قائمة جميع المستخدمين المسجلين
app.get('/api/users', (_req, res) => {
  res.json({ users: getAllUsers() });
});

// خريطة المستخدمين المتصلين: username -> WebSocket
const onlineClients = new Map();

function broadcastOnlineUsers() {
  const onlineList = Array.from(onlineClients.keys());
  const payload = JSON.stringify({
    type: 'online_users',
    users: onlineList,
  });
  for (const client of onlineClients.values()) {
    if (client.readyState === client.OPEN) {
      client.send(payload);
    }
  }
}

wss.on('connection', (ws) => {
  let currentUser = null;

  ws.on('message', (rawData) => {
    let msg;
    try {
      msg = JSON.parse(rawData.toString());
    } catch (err) {
      console.error('[ws] رسالة غير صالحة:', err.message);
      return;
    }

    switch (msg.type) {
      case 'login': {
        const username = (msg.username || '').trim();
        if (!username || username.length > 32) {
          ws.send(
            JSON.stringify({
              type: 'error',
              message: 'اسم المستخدم غير صالح',
            })
          );
          return;
        }

        // إذا كان المستخدم متصلاً بالفعل، أغلق الجلسة السابقة
        if (onlineClients.has(username)) {
          const oldWs = onlineClients.get(username);
          if (oldWs !== ws && oldWs.readyState === oldWs.OPEN) {
            oldWs.send(
              JSON.stringify({
                type: 'error',
                message: 'تم تسجيل الدخول من جهاز آخر',
              })
            );
            oldWs.close();
          }
        }

        currentUser = username;
        registerUser(username);
        onlineClients.set(username, ws);

        // أرسل رسالة الترحيب وسجل الرسائل
        ws.send(
          JSON.stringify({
            type: 'welcome',
            username,
            message: `مرحبا الساع يا ${username}`,
          })
        );

        const history = getMessagesForUser(username);
        ws.send(
          JSON.stringify({
            type: 'history',
            messages: history,
          })
        );

        // أعلم الجميع بقائمة المتصلين الجديدة
        broadcastOnlineUsers();
        console.log(`[ws] دخول: ${username} (المتصلون: ${onlineClients.size})`);
        break;
      }

      case 'message': {
        if (!currentUser) {
          ws.send(
            JSON.stringify({ type: 'error', message: 'يجب تسجيل الدخول أولاً' })
          );
          return;
        }
        const recipient = (msg.to || '').trim();
        const text = (msg.text || '').trim();
        if (!recipient || !text) return;
        if (text.length > 2000) {
          ws.send(
            JSON.stringify({
              type: 'error',
              message: 'الرسالة طويلة جداً (الحد الأقصى 2000 حرف)',
            })
          );
          return;
        }

        const saved = saveMessage(currentUser, recipient, text);
        const payload = JSON.stringify({
          type: 'message',
          ...saved,
        });

        // أرسل للمستلم إذا كان متصلاً
        const recipientWs = onlineClients.get(recipient);
        if (recipientWs && recipientWs.readyState === recipientWs.OPEN) {
          recipientWs.send(payload);
        }

        // ارسل نسخة للمرسل (تأكيد + معرّف)
        ws.send(payload);
        break;
      }

      case 'typing': {
        if (!currentUser) return;
        const recipient = (msg.to || '').trim();
        const recipientWs = onlineClients.get(recipient);
        if (recipientWs && recipientWs.readyState === recipientWs.OPEN) {
          recipientWs.send(
            JSON.stringify({
              type: 'typing',
              from: currentUser,
            })
          );
        }
        break;
      }

      default:
        console.warn('[ws] نوع رسالة غير معروف:', msg.type);
    }
  });

  ws.on('close', () => {
    if (currentUser && onlineClients.get(currentUser) === ws) {
      onlineClients.delete(currentUser);
      broadcastOnlineUsers();
      console.log(`[ws] خروج: ${currentUser} (المتصلون: ${onlineClients.size})`);
    }
  });

  ws.on('error', (err) => {
    console.error('[ws] خطأ:', err.message);
  });
});

initDB();

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  ╔══════════════════════════════════════╗');
  console.log('  ║                                      ║');
  console.log('  ║     مطراش — مرحبا الساع              ║');
  console.log('  ║                                      ║');
  console.log('  ╚══════════════════════════════════════╝');
  console.log('');
  console.log(`  الخادم يعمل على: http://localhost:${PORT}`);
  console.log('');
});
