/**
 * مطراش — طبقة التخزين البسيطة
 * تستخدم ملف JSON لتخزين الرسائل والمستخدمين (مناسبة للـ MVP).
 * في المراحل اللاحقة: ترقية إلى PostgreSQL أو SQLite.
 */

const fs = require('fs');
const path = require('path');

const DB_FILE = path.join(__dirname, '..', 'mitrash-data.json');

let data = {
  users: [],
  messages: [],
};

let messageIdCounter = 0;

function initDB() {
  if (fs.existsSync(DB_FILE)) {
    try {
      const raw = fs.readFileSync(DB_FILE, 'utf-8');
      const parsed = JSON.parse(raw);
      data = {
        users: Array.isArray(parsed.users) ? parsed.users : [],
        messages: Array.isArray(parsed.messages) ? parsed.messages : [],
      };
      messageIdCounter = data.messages.reduce(
        (max, m) => Math.max(max, m.id || 0),
        0
      );
      console.log(
        `[db] تم تحميل ${data.users.length} مستخدم و ${data.messages.length} رسالة`
      );
    } catch (err) {
      console.error('[db] فشل تحميل قاعدة البيانات، بداية من جديد:', err.message);
      data = { users: [], messages: [] };
    }
  } else {
    persist();
    console.log('[db] تم إنشاء قاعدة بيانات جديدة');
  }
}

function persist() {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2), 'utf-8');
  } catch (err) {
    console.error('[db] فشل حفظ البيانات:', err.message);
  }
}

function registerUser(username) {
  if (!username || typeof username !== 'string') return false;
  const trimmed = username.trim();
  if (trimmed.length === 0 || trimmed.length > 32) return false;
  if (!data.users.includes(trimmed)) {
    data.users.push(trimmed);
    persist();
  }
  return true;
}

function getAllUsers() {
  return [...data.users];
}

function saveMessage(sender, recipient, text) {
  const message = {
    id: ++messageIdCounter,
    sender,
    recipient,
    text,
    timestamp: Date.now(),
  };
  data.messages.push(message);
  persist();
  return message;
}

function getMessagesBetween(user1, user2) {
  return data.messages.filter(
    (m) =>
      (m.sender === user1 && m.recipient === user2) ||
      (m.sender === user2 && m.recipient === user1)
  );
}

function getMessagesForUser(username) {
  return data.messages.filter(
    (m) => m.sender === username || m.recipient === username
  );
}

module.exports = {
  initDB,
  registerUser,
  getAllUsers,
  saveMessage,
  getMessagesBetween,
  getMessagesForUser,
};
