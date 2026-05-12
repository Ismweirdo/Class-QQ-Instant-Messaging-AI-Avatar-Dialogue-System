<template>
  <div class="chat-window">
    <!-- Header -->
    <div class="chat-header">
      <div class="chat-header-info">
        <el-avatar class="chat-avatar" :style="type === 'group' ? 'background: linear-gradient(135deg, #f59e0b, #ef4444);' : ''">
          {{ targetName[0] }}
        </el-avatar>
        <div class="chat-title-wrap">
          <div class="chat-title">{{ targetName }}</div>
          <div class="chat-subtitle">{{ type === 'group' ? '群聊' : '在线' }}</div>
        </div>
      </div>
      <div class="chat-header-actions">
        <button class="header-action-btn">
          <el-icon><Phone /></el-icon>
        </button>
        <button class="header-action-btn">
          <el-icon><VideoCamera /></el-icon>
        </button>
        <button class="header-action-btn">
          <el-icon><More /></el-icon>
        </button>
      </div>
    </div>

    <!-- Messages -->
    <div class="chat-messages" ref="msgContainer" :style="backgroundStyle">
      <div v-if="loading" class="loading-hint">
        <div class="loading-spinner"></div>
        <div class="loading-text">加载中...</div>
      </div>
      <div v-for="msg in chatStore.currentMessages" :key="msg.id || msg.clientMessageId">
        <MessageBubble :message="msg" :isMine="msg.senderId === userStore.userId"
          @reply="onReplyMessage(msg)" @recall="onRecallMessage(msg)" />
      </div>
      <div ref="msgEnd"></div>
    </div>

    <!-- Reply bar -->
    <div v-if="replyingTo" class="reply-bar">
      <div class="reply-content">
        <el-avatar class="reply-avatar">{{ replyingTo.senderName[0] }}</el-avatar>
        <div class="reply-text">
          <span class="reply-name">{{ replyingTo.senderName }}</span>
          <span>{{ truncate(replyingTo.content, 60) }}</span>
        </div>
      </div>
      <div class="reply-close" @click="replyingTo = null">
        <el-icon><Close /></el-icon>
      </div>
    </div>

    <!-- Input -->
    <div class="chat-input">
      <div class="input-tools">
        <button class="tool-btn">
          <el-icon><Paperclip /></el-icon>
        </button>
        <button class="tool-btn">
          <el-icon><Picture /></el-icon>
        </button>
      </div>
      <div class="input-area">
        <el-input v-model="input" type="textarea" :rows="1" placeholder="输入消息..."
          @keyup.enter.exact="sendMessage" resize="none" />
      </div>
      <el-button type="primary" class="send-btn" @click="sendMessage" :disabled="!input.trim()">
        <el-icon><ArrowRight /></el-icon>
        <span>发送</span>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onUnmounted, computed } from 'vue'
import { useUserStore } from '../store/user'
import { useChatStore } from '../store/chat'
import { sendChatMessage, subscribeGroupMessages, unsubscribeGroupMessages } from '../utils/websocket'
import { recallMessage } from '../api/message'
import { ElMessage } from 'element-plus'
import { Phone, VideoCamera, More, Close, Paperclip, Picture, ArrowRight } from '@element-plus/icons-vue'
import MessageBubble from './MessageBubble.vue'

const props = defineProps({
  type: { type: String, required: true },
  targetId: { type: Number, required: true },
  targetName: { type: String, required: true }
})

const userStore = useUserStore()
const chatStore = useChatStore()
const input = ref('')
const replyingTo = ref(null)
const msgContainer = ref(null)
const msgEnd = ref(null)
const loading = ref(false)

const STORAGE_KEY = 'chat-background'

const chatKey = computed(() => `${props.type}_${props.targetId}`)

const backgroundStyle = computed(() => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (!stored) return {}
    const bg = JSON.parse(stored)
    if (bg.type === 'color') return { backgroundColor: bg.value }
    if (bg.type === 'gradient') return { background: bg.value }
    if (bg.type === 'image') return {
      backgroundImage: `url(${bg.value})`,
      backgroundSize: 'cover',
      backgroundPosition: 'center'
    }
  } catch { /* ignore */ }
  return {}
})

async function loadHistory() {
  chatStore.setCurrentChat(chatKey.value)
  loading.value = true
  try {
    await chatStore.fetchHistory(props.type, props.targetId)
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

onMounted(() => {
  loadHistory()
  if (props.type === 'group') {
    subscribeGroupMessages(props.targetId)
  }
})

onUnmounted(() => {
  if (props.type === 'group') {
    unsubscribeGroupMessages(props.targetId)
  }
})

// Watch for target changes (switching contacts)
watch(() => props.targetId, () => {
  loadHistory()
  if (props.type === 'group') {
    subscribeGroupMessages(props.targetId)
  }
})

// Auto-scroll on new messages
watch(() => chatStore.currentMessages.length, () => {
  nextTick(() => scrollToBottom())
})

function sendMessage() {
  if (!input.value.trim()) return

  const dto = {
    content: input.value.trim(),
    messageType: props.type === 'private' ? 0 : 1,
    targetId: props.targetId,
    replyToId: replyingTo.value?.id || null,
    contentType: 0,
    clientMessageId: generateUUID()
  }

  const sent = sendChatMessage(dto)
  if (sent) {
    input.value = ''
    replyingTo.value = null
  } else {
    ElMessage.error('连接已断开，请刷新页面')
  }
}

function onReplyMessage(msg) {
  replyingTo.value = msg
}

async function onRecallMessage(msg) {
  try {
    await recallMessage(msg.id)
    chatStore.updateMessage(chatKey.value, msg.id, { content: '[消息已撤回]' })
  } catch (e) {
    // Handled by interceptor
  }
}

function scrollToBottom() {
  nextTick(() => {
    msgEnd.value?.scrollIntoView({ behavior: 'smooth' })
  })
}

function truncate(text, len) {
  if (!text) return ''
  return text.length > len ? text.substring(0, len) + '...' : text
}

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}
</script>

<style scoped>
.chat-window {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-secondary);
}

.chat-header {
  padding: 20px 24px;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-light);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chat-header-info {
  display: flex;
  align-items: center;
  gap: 14px;
}

.chat-avatar {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
}

.chat-title-wrap {
  display: flex;
  flex-direction: column;
}

.chat-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
}

.chat-subtitle {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 2px;
}

.chat-header-actions {
  display: flex;
  gap: 8px;
}

.header-action-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--bg-secondary);
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.header-action-btn:hover {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: linear-gradient(180deg, var(--bg-secondary) 0%, rgba(99, 102, 241, 0.03) 100%);
}

.chat-messages::-webkit-scrollbar {
  width: 4px;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: var(--text-muted);
  border-radius: var(--radius-full);
}

.reply-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: linear-gradient(90deg, rgba(99, 102, 241, 0.08), rgba(139, 92, 246, 0.08));
  border-top: 1px solid var(--border-light);
  border-bottom: 1px solid var(--border-light);
}

.reply-content {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.reply-avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
}

.reply-text {
  max-width: 70%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.reply-name {
  color: var(--primary-color);
  font-weight: 500;
}

.reply-close {
  padding: 6px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.reply-close:hover {
  background: rgba(0, 0, 0, 0.05);
}

.chat-input {
  display: flex;
  padding: 16px 24px;
  background: var(--bg-primary);
  border-top: 1px solid var(--border-light);
  gap: 12px;
  align-items: flex-end;
}

.input-tools {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.tool-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--bg-secondary);
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.tool-btn:hover {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.input-area {
  flex: 1;
}

.input-area :deep(.el-textarea__wrapper) {
  border-radius: 16px;
  background: var(--bg-secondary);
  border: none;
  box-shadow: none;
  padding: 12px 16px;
}

.input-area :deep(.el-textarea__inner) {
  resize: none;
  font-size: 15px;
  line-height: 1.5;
  min-height: 44px;
}

.send-btn {
  height: 44px;
  border-radius: 12px;
  font-weight: 600;
  padding: 0 24px;
}

.send-btn:disabled {
  opacity: 0.5;
  transform: none;
}

.loading-hint {
  text-align: center;
  color: var(--text-muted);
  padding: 40px;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 12px;
  border: 3px solid var(--border-light);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-text {
  font-size: 14px;
}
</style>
