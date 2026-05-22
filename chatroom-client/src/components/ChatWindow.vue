<template>
  <div class="chat-window">
    <!-- Header -->
    <div class="chat-header">
      <div class="chat-header-info">
        <el-avatar class="chat-avatar" :src="targetAvatar || undefined"
          :style="type === 'group' ? 'background: linear-gradient(135deg, #f59e0b, #ef4444);' : ''">
          {{ targetName[0] }}
        </el-avatar>
        <div class="chat-title-wrap">
          <div class="chat-title">{{ targetName }}</div>
          <div class="chat-subtitle">{{ isBot ? 'AI 机器人' : (type === 'group' ? '群聊' : '在线') }}</div>
        </div>
      </div>
      <div class="chat-header-actions">
        <template v-if="isBot">
          <el-popover placement="bottom" :width="360" trigger="click" :hide-after="0">
            <template #reference>
              <button class="header-action-btn provider-btn" title="AI 厂商设置">
                <el-icon><Setting /></el-icon>
              </button>
            </template>
            <ProviderSelector :botUserId="targetId" @updated="onProviderUpdated" />
          </el-popover>
          <div class="active-mode-toggle" :class="{ active: activeModeEnabled }">
            <span class="toggle-label">主动聊天</span>
            <el-switch v-model="activeModeEnabled" size="small" @change="onActiveToggle" :loading="activeModeLoading" />
          </div>
          <el-select v-if="activeModeEnabled" v-model="activeInterval" size="small" class="interval-select"
            @change="onIntervalChange" :disabled="activeModeLoading">
            <el-option :value="15" label="15秒" />
            <el-option :value="30" label="30秒" />
            <el-option :value="60" label="1分钟" />
            <el-option :value="120" label="2分钟" />
            <el-option :value="300" label="5分钟" />
          </el-select>
          <el-popover placement="bottom" :width="300" trigger="click" :hide-after="0">
            <template #reference>
              <button class="header-action-btn" title="快速补充 Bot 设定">
                <el-icon><DocumentAdd /></el-icon>
              </button>
            </template>
            <div style="font-size:13px;font-weight:600;margin-bottom:8px;color:var(--text-primary)">快速补充 Bot 设定</div>
            <el-input
              v-model="quickUpdateText"
              type="textarea"
              :rows="3"
              placeholder="输入要添加的内容，如：他最近喜欢上玩apex"
              style="margin-bottom:10px"
            />
            <div style="display:flex;justify-content:flex-end;gap:8px">
              <el-button size="small" @click="quickUpdateText = ''">清空</el-button>
              <el-button size="small" type="primary" @click="onQuickUpdate" :loading="quickUpdating" :disabled="!quickUpdateText.trim()">
                补充设定
              </el-button>
            </div>
          </el-popover>
        </template>
        <button class="header-action-btn" @click="$emit('showHistory')" title="聊天记录">
          <el-icon><Clock /></el-icon>
        </button>
        <template v-if="type === 'group'">
          <el-popover placement="bottom" :width="260" trigger="click">
            <template #reference>
              <button class="header-action-btn" title="群机器人管理">
                <el-icon><Setting /></el-icon>
              </button>
            </template>
            <div class="bot-auto-panel">
              <div style="font-size:13px;font-weight:600;margin-bottom:8px;color:var(--text-primary)">群机器人自动聊天</div>
              <div v-if="groupBots.length === 0" style="color:var(--text-muted);font-size:12px;text-align:center;padding:12px">暂无机器人</div>
              <div v-for="b in groupBots" :key="b.userId" class="bot-toggle-item">
                <span>{{ b.nickname }}</span>
                <el-switch v-model="b.enabled" size="small" @change="onBotToggle(b)" />
              </div>
              <el-button v-if="groupBots.length > 0" size="small" type="primary" @click="saveBotAutoChat" :loading="savingBots" style="width:100%;margin-top:8px">保存设置</el-button>
            </div>
          </el-popover>
        </template>
        <button class="header-action-btn" @click="handleClearHistory" title="清除聊天记录">
          <el-icon><Delete /></el-icon>
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
          @reply="onReplyMessage(msg)" @recall="onRecallMessage(msg)" @deleteMsg="onDeleteMessage(msg)" />
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
        <template v-if="type === 'group'">
          <el-popover placement="top" :width="200" trigger="click">
            <template #reference>
              <button class="tool-btn" title="@提及">
                <span style="font-weight:700;font-size:16px">@</span>
              </button>
            </template>
            <div class="mention-list">
              <div class="mention-item mention-all" @click="insertAtAll()">@全体成员</div>
              <div v-for="m in groupMembers" :key="m.userId" class="mention-item"
                @click="insertMention(m)">@{{ m.nickname || m.username }}</div>
              <div v-if="groupMembers === null" style="color:var(--text-muted);text-align:center;padding:12px">加载中...</div>
              <div v-else-if="groupMembers.length === 0" style="color:var(--text-muted);text-align:center;padding:12px">暂无成员</div>
            </div>
          </el-popover>
        </template>
        <button class="tool-btn" @click="$refs.fileInput.click()" title="发送文件">
          <el-icon><Paperclip /></el-icon>
        </button>
        <button class="tool-btn" @click="$refs.imgInput.click()" title="发送图片">
          <el-icon><Picture /></el-icon>
        </button>
        <input ref="fileInput" type="file" style="display:none" @change="onFileSelect($event, false)" />
        <input ref="imgInput" type="file" accept="image/*" style="display:none" @change="onFileSelect($event, true)" />
      </div>
      <div class="input-area">
        <el-input v-model="input" type="textarea" :rows="1" placeholder="输入消息..."
          @keyup.enter.exact="sendMessage" resize="none" />
      </div>
      <el-button type="primary" class="send-btn" @click="sendMessage" :disabled="!input.trim() && !uploading">
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
import { useContactStore } from '../store/contact'
import { sendChatMessage, subscribeGroupMessages, unsubscribeGroupMessages } from '../utils/websocket'
import { recallMessage } from '../api/message'
import { setActiveMode, getActiveMode, updateBotSkillText } from '../api/bot'
import { clearChatHistory, deleteMessagePermanently } from '../api/message'
import { getGroupMembers, getGroupBotAutoChat, setGroupBotAutoChat } from '../api/group'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import { Close, Paperclip, Picture, ArrowRight, DocumentAdd, Setting, Clock, Delete } from '@element-plus/icons-vue'
import MessageBubble from './MessageBubble.vue'
import ProviderSelector from './ProviderSelector.vue'

const props = defineProps({
  type: { type: String, required: true },
  targetId: { type: Number, required: true },
  targetName: { type: String, required: true },
  targetAvatar: { type: String, default: '' }
})
defineEmits(['showHistory'])

const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()
const input = ref('')
const replyingTo = ref(null)
const msgContainer = ref(null)
const msgEnd = ref(null)
const loading = ref(false)

// Active mode state
const activeModeEnabled = ref(false)
const activeInterval = ref(60)
const activeModeLoading = ref(false)

const isBot = computed(() => {
  const friend = contactStore.friendList.find(f => f.friendId === props.targetId)
  return friend?.isBot === 1
})

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

async function fetchActiveMode() {
  if (!isBot.value) return
  try {
    const res = await getActiveMode(props.targetId)
    // Response interceptor already unwrapped data: res = { enabled, intervalSeconds }
    activeModeEnabled.value = res?.enabled ?? false
    activeInterval.value = res?.intervalSeconds ?? 60
  } catch {
    activeModeEnabled.value = false
  }
}

async function onActiveToggle(val) {
  activeModeLoading.value = true
  try {
    await setActiveMode(props.targetId, val, activeInterval.value)
    ElMessage.success(val ? '已开启主动聊天模式' : '已关闭主动聊天模式，机器人仅被动回复')
  } catch {
    activeModeEnabled.value = !val
    ElMessage.error('操作失败')
  } finally {
    activeModeLoading.value = false
  }
}

async function onIntervalChange(val) {
  if (!activeModeEnabled.value) return
  activeModeLoading.value = true
  try {
    await setActiveMode(props.targetId, true, val)
  } catch {
    ElMessage.error('更新间隔失败')
  } finally {
    activeModeLoading.value = false
  }
}

// Quick bot text update
const quickUpdateText = ref('')
const quickUpdating = ref(false)

async function onQuickUpdate() {
  if (!quickUpdateText.value.trim()) return
  quickUpdating.value = true
  try {
    await updateBotSkillText(props.targetId, quickUpdateText.value.trim())
    ElMessage.success(`已补充设定：${quickUpdateText.value.trim().substring(0, 20)}...`)
    quickUpdateText.value = ''
  } catch {
    ElMessage.error('补充设定失败')
  } finally {
    quickUpdating.value = false
  }
}

function onProviderUpdated() {}

const groupMembers = ref(null)
async function loadGroupMembers() {
  if (props.type !== 'group') return
  try { groupMembers.value = await getGroupMembers(props.targetId) || [] }
  catch { groupMembers.value = [] }
}
function insertMention(member) {
  input.value += '@' + (member.nickname || member.username) + ' '
}
function insertAtAll() {
  input.value += '@全体成员 '
}

const groupBots = ref([])
const savingBots = ref(false)
async function loadGroupBotConfig() {
  if (props.type !== 'group') return
  try {
    const config = await getGroupBotAutoChat(props.targetId)
    const enabledIds = new Set((config?.enabledBots || []).map(b => b.botUserId))
    const members = groupMembers.value || []
    groupBots.value = members.filter(m => m.isBot).map(m => ({ ...m, enabled: enabledIds.has(m.userId) }))
  } catch { groupBots.value = [] }
}
async function loadGroupMembersAndBots() {
  await loadGroupMembers()
  await loadGroupBotConfig()
}
function onBotToggle() {}
async function saveBotAutoChat() {
  savingBots.value = true
  try {
    const enabledIds = groupBots.value.filter(b => b.enabled).map(b => b.userId)
    await setGroupBotAutoChat(props.targetId, enabledIds)
    ElMessage.success(`已保存，${enabledIds.length} 个机器人启用群聊`)
  } catch { ElMessage.error('保存失败') }
  finally { savingBots.value = false }
}

async function handleClearHistory() {
  try {
    await ElMessageBox.confirm('确定要清除与该联系人的所有聊天记录吗？此操作不可恢复。', '清除聊天记录', {
      type: 'warning', confirmButtonText: '确定清除', cancelButtonText: '取消'
    })
    await clearChatHistory(props.targetId)
    chatStore.messages[chatKey.value] = []
    ElMessage.success('聊天记录已清除')
  } catch { /* cancelled */ }
}

const uploading = ref(false)
async function onFileSelect(e, isImage) {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const token = localStorage.getItem('token')
    const res = await axios.post('/api/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data', Authorization: token ? `Bearer ${token}` : '' }
    })
    const d = res.data.data || res.data
    if (d && d.url) {
      const contentType = isImage ? 1 : 2
      const content = isImage ? `[图片] ${d.url}` : `[文件] ${d.originalName} ${d.url}`
      const dto = { content, messageType: 0, targetId: props.targetId, contentType, clientMessageId: generateUUID() }
      sendChatMessage(dto)
      ElMessage.success(isImage ? '图片已发送' : '文件已发送')
    }
  } catch { ElMessage.error('上传失败') }
  finally {
    uploading.value = false
    e.target.value = ''
  }
}

onMounted(() => {
  loadHistory()
  fetchActiveMode()
  loadGroupMembersAndBots()
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
  fetchActiveMode()
  loadGroupMembersAndBots()
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
  } catch { /* handled by interceptor */ }
}

async function onDeleteMessage(msg) {
  try {
    await ElMessageBox.confirm('确定要删除这条消息吗？', '删除消息', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await deleteMessagePermanently(msg.id)
    // Remove from local state
    const msgs = chatStore.messages[chatKey.value]
    if (msgs) {
      chatStore.messages[chatKey.value] = msgs.filter(m => m.id !== msg.id && m.clientMessageId !== msg.clientMessageId)
    }
    ElMessage.success('消息已删除')
  } catch { /* cancelled */ }
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

.active-mode-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 10px;
  background: var(--bg-secondary);
  transition: all 0.3s ease;
}

.active-mode-toggle.active {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.15), rgba(139, 92, 246, 0.15));
}

.toggle-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
  white-space: nowrap;
  transition: color 0.3s ease;
}

.active-mode-toggle.active .toggle-label {
  color: var(--primary-color);
}

.interval-select {
  width: 90px;
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
