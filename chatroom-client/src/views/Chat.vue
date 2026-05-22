<template>
  <div class="chat-layout">
    <!-- Left Sidebar -->
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="user-info">
          <div class="user-avatar" @click="showProfile = true" title="个人资料">
            <img v-if="userStore.user?.avatar && userStore.user.avatar !== '/avatars/default.png'" :src="userStore.user.avatar" class="avatar-img" />
            <span v-else>{{ (userStore.nickname || userStore.username || '?')[0] }}</span>
          </div>
          <div class="user-details">
            <div class="nickname">{{ userStore.nickname || userStore.username }}</div>
            <div class="status-indicator">
              <span class="status-dot"></span>
              <span>在线</span>
            </div>
          </div>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="dropdown-trigger">
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人设置</el-dropdown-item>
                <el-dropdown-item command="background">聊天背景</el-dropdown-item>
                <el-dropdown-item command="deleteAccount" divided>注销账户</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="header-actions">
          <button class="action-btn" @click="showCreateGroup = true" title="创建群聊">
            <el-icon><Plus /></el-icon>
          </button>
          <button class="action-btn" @click="showImportBots = true" title="导入聊天记录生成机器人">
            <el-icon><Download /></el-icon>
          </button>
          <button class="action-btn" @click="showImportCharacter = true" title="从URL或文件导入人物Skill">
            <el-icon><UserFilled /></el-icon>
          </button>
        </div>
      </div>
      <ContactList @select="onSelectContact" @group-info="onGroupInfo" @refresh="refreshContacts" />
    </div>

    <!-- Right Chat Area -->
    <div class="chat-area">
      <ChatWindow v-if="activeChat" :key="activeChat.key"
        :type="activeChat.type"
        :targetId="activeChat.id"
        :targetName="activeChat.name"
        :targetAvatar="activeChatAvatar"
        @back="activeChat = null"
        @show-history="showHistory = true" />
      <div v-else class="no-chat">
        <div class="no-chat-icon">
          <el-icon :size="64" color="var(--primary-color)"><ChatDotRound /></el-icon>
        </div>
        <div class="no-chat-title">选择一个联系人开始聊天</div>
        <div class="no-chat-subtitle">您可以从左侧列表中选择好友或群聊</div>
      </div>
    </div>

    <!-- Add Friend Dialog -->
    <AddFriendDialog v-model:visible="showAddFriend" @done="onAddFriendDone" />

    <!-- Create Group Dialog -->
    <CreateGroupDialog v-model:visible="showCreateGroup" @done="onGroupCreated" />

    <!-- Friend Requests Dialog -->
    <el-dialog v-model="showFriendRequests" title="好友申请" width="450px">
      <div v-if="contactStore.pendingRequests.length === 0" class="empty-center">
        暂无待处理的好友申请
      </div>
      <div v-for="req in contactStore.pendingRequests" :key="req.id" class="request-item">
        <div class="request-info">
          <div class="request-avatar">{{ (req.nickname || req.username)[0] }}</div>
          <div>
            <div class="request-name">{{ req.nickname || req.username }}</div>
            <div class="request-username">@{{ req.username }}</div>
          </div>
        </div>
        <div class="request-actions">
          <el-button type="primary" size="small" @click="handleAccept(req.friendId)">接受</el-button>
          <el-button size="small" @click="handleReject(req.friendId)">拒绝</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- Import Bots Dialog -->
    <ImportBotsDialog v-model:visible="showImportBots" @done="onBotsImported" />

    <!-- QQ Import Dialog -->
    <QQImportDialog v-model:visible="showQQImport" @done="onBotsImported" />

    <!-- Import Character Dialog -->
    <ImportCharacterDialog v-model:visible="showImportCharacter" @imported="onBotsImported" />

    <!-- Group Info Dialog -->
    <GroupInfoDialog v-model:visible="showGroupInfo" :group="selectedGroup" @refresh="refreshContacts" />

    <!-- Profile Dialog -->
    <ProfileDialog v-model:visible="showProfile" :display-name="userStore.nickname || userStore.username" @updated="onProfileUpdated" />

    <!-- Background Settings Dialog -->
    <BackgroundSettings v-model:visible="showBackground" />
    <ChatHistoryDialog v-model:visible="showHistory" :targetId="activeChat?.id" :targetName="activeChat?.name" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { useContactStore } from '../store/contact'
import { useChatStore } from '../store/chat'
import { connectWebSocket, disconnectWebSocket, addMessageHandler, removeMessageHandler, addPresenceHandler, removePresenceHandler, subscribeGroupMessages, addStreamHandler, removeStreamHandler, subscribeGroupStream, unsubscribeGroupStream } from '../utils/websocket'
import { acceptFriendRequest, rejectFriendRequest } from '../api/friend'
import { deleteAccount } from '../api/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, UserFilled, Plus, Message, ChatDotRound, Download, ChatDotSquare } from '@element-plus/icons-vue'
import ContactList from '../components/ContactList.vue'
import ChatWindow from '../components/ChatWindow.vue'
import AddFriendDialog from '../components/AddFriendDialog.vue'
import CreateGroupDialog from '../components/CreateGroupDialog.vue'
import GroupInfoDialog from '../components/GroupInfoDialog.vue'
import ImportBotsDialog from '../components/ImportBotsDialog.vue'
import QQImportDialog from '../components/QQImportDialog.vue'
import ImportCharacterDialog from '../components/ImportCharacterDialog.vue'
import ProfileDialog from '../components/ProfileDialog.vue'
import BackgroundSettings from '../components/BackgroundSettings.vue'
import ChatHistoryDialog from '../components/ChatHistoryDialog.vue'

const router = useRouter()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()

const showAddFriend = ref(false)
const showCreateGroup = ref(false)
const showImportBots = ref(false)
const showQQImport = ref(false)
const showImportCharacter = ref(false)
const showHistory = ref(false)

const activeChatAvatar = computed(() => {
  if (!activeChat.value || activeChat.value.type !== 'private') return ''
  const friend = contactStore.friendList.find(f => f.friendId === activeChat.value.id)
  return friend?.avatar || ''
})
const showFriendRequests = ref(false)
const showGroupInfo = ref(false)
const showProfile = ref(false)
const showBackground = ref(false)
const selectedGroup = ref(null)
const activeChat = ref(null)

function onSelectContact(contact) {
  activeChat.value = contact
}

function onGroupInfo(group) {
  selectedGroup.value = { id: group.id, name: group.name }
  showGroupInfo.value = true
}

function openFriendRequests() {
  contactStore.fetchPendingRequests()
  showFriendRequests.value = true
}

async function onAddFriendDone() {
  showAddFriend.value = false
  await refreshContacts()
}

async function onGroupCreated() {
  showCreateGroup.value = false
  await refreshContacts()
}

async function onBotsImported() {
  showImportBots.value = false
  showQQImport.value = false
  await refreshContacts()
}

async function refreshContacts() {
  await contactStore.fetchAll()
}

function handleMessage(msg) {
  let key
  if (msg.messageType === 0) {
    const otherId = msg.senderId === userStore.userId ? msg.targetId : msg.senderId
    key = `private_${otherId}`
  } else {
    key = `group_${msg.targetId}`
  }
  chatStore.addMessage(key, {
    id: msg.messageId,
    messageType: msg.messageType,
    senderId: msg.senderId,
    senderName: msg.senderName,
    senderAvatar: msg.senderAvatar,
    targetId: msg.targetId,
    replyToId: msg.replyToId,
    replyToContent: msg.replyToContent,
    replyToSenderName: msg.replyToSenderName,
    content: msg.content,
    contentType: msg.contentType,
    createdAt: msg.createdAt
  })
}

function handleStream(data) {
  if (data.type === 'BOT_STREAM_START') {
    const chatKey = data.isGroup ? `group_${data.targetId}` : `private_${data.botUserId}`
    chatStore.addMessage(chatKey, {
      id: 'stream_' + data.botUserId,
      senderId: data.botUserId,
      senderName: '...',
      content: '思考中...',
      contentType: 0,
      createdAt: new Date().toISOString(),
      _streaming: true,
      _botUserId: data.botUserId
    })
  } else if (data.type === 'BOT_STREAM_CHUNK') {
    const chatKey = data.isGroup ? `group_${data.targetId}` : `private_${data.botUserId}`
    const msgs = chatStore.messages[chatKey]
    if (msgs) {
      const msg = msgs.find(m => m._streaming && m._botUserId === data.botUserId)
      if (msg) msg.content += data.token
    }
  } else if (data.type === 'BOT_STREAM_END') {
    const chatKey = data.isGroup ? `group_${data.targetId}` : `private_${data.botUserId}`
    const msgs = chatStore.messages[chatKey]
    if (msgs) {
      const msg = msgs.find(m => m._streaming && m._botUserId === data.botUserId)
      if (msg) {
        msg._streaming = false
        msg.content = data.content
      }
    }
  }
}

function handlePresence(data) {
  contactStore.updateFriendStatus(data.userId, data.status === 'ONLINE')
}

async function handleAccept(friendId) {
  await acceptFriendRequest(friendId)
  ElMessage.success('已接受好友申请')
  await refreshContacts()
}

async function handleReject(friendId) {
  await rejectFriendRequest(friendId)
  ElMessage.success('已拒绝好友申请')
  await refreshContacts()
}

function handleUserCommand(command) {
  if (command === 'profile') {
    showProfile.value = true
  } else if (command === 'background') {
    showBackground.value = true
  } else if (command === 'deleteAccount') {
    confirmDeleteAccount()
  } else if (command === 'logout') {
    disconnectWebSocket()
    userStore.logout()
    router.push('/login')
  }
}

async function confirmDeleteAccount() {
  try {
    await ElMessageBox.confirm(
      '账户注销后将永久删除您的所有数据，包括好友关系、消息记录、群组成员资格。此操作不可撤销！',
      '确认注销账户',
      { confirmButtonText: '确认注销', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteAccount()
    disconnectWebSocket()
    userStore.logout()
    ElMessage.success('账户已注销')
    router.push('/login')
  } catch (e) {
    if (e !== 'cancel') {
      // API error handled by interceptor
    }
  }
}

function onProfileUpdated() {
  ElMessage.success('个人资料已更新')
}

async function subscribeAllGroups() {
  const groups = contactStore.groupList
  for (const g of groups) {
    subscribeGroupMessages(g.id)
    subscribeGroupStream(g.id)
  }
}

onMounted(async () => {
  await userStore.fetchUser()
  await contactStore.fetchAll()

  const token = localStorage.getItem('token')
  if (token) {
    try {
      await connectWebSocket(token)
      addMessageHandler(handleMessage)
      addPresenceHandler(handlePresence)
      addStreamHandler(handleStream)
      // Subscribe to all group topics
      subscribeAllGroups()
    } catch (e) {
      console.error('WebSocket connection failed:', e)
    }
  }
})

onUnmounted(() => {
  removeMessageHandler(handleMessage)
  removePresenceHandler(handlePresence)
  disconnectWebSocket()
})
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100vh;
  background: var(--bg-secondary);
}

.sidebar {
  width: 340px;
  background: var(--bg-primary);
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border-color);
}

.sidebar-header {
  padding: 16px 20px;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-light);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.user-avatar {
  width: 42px; height: 42px; border-radius: 12px;
  background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 600; color: white; overflow: hidden;
  cursor: pointer; transition: transform 0.2s; flex-shrink: 0;
}
.user-avatar:hover { transform: scale(1.08); }
.user-avatar .avatar-img { width: 100%; height: 100%; object-fit: cover; }

.user-details { flex: 1; min-width: 0; }
.nickname { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.status-indicator { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-muted); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success-color); flex-shrink: 0; }

.status-dot {
  width: 8px;
  height: 8px;
  background: #22c55e;
  border-radius: 50%;
  margin-right: 6px;
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(1.1);
  }
}

.dropdown-trigger {
  padding: 8px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.15);
  transition: all 0.3s ease;
  cursor: pointer;
}

.dropdown-trigger:hover {
  background: rgba(255, 255, 255, 0.25);
}

.header-actions {
  display: flex;
  gap: 10px;
}

.action-btn {
  width: 36px; height: 36px; border-radius: 10px;
  background: var(--bg-secondary); border: none;
  color: var(--text-secondary); display: flex;
  align-items: center; justify-content: center;
  cursor: pointer; transition: all 0.2s;
}
.action-btn:hover { background: var(--bg-hover); color: var(--primary-color); }

.header-actions {
  display: flex; gap: 6px; flex-wrap: wrap;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-secondary);
}

.no-chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: var(--text-muted);
  gap: 20px;
  font-size: 16px;
}

.no-chat-icon {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.1));
  display: flex;
  align-items: center;
  justify-content: center;
  animation: icon-float 3s ease-in-out infinite;
}

@keyframes icon-float {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-10px);
  }
}

.no-chat-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.no-chat-subtitle {
  font-size: 14px;
  color: var(--text-muted);
}

.request-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid var(--border-light);
  transition: all 0.2s ease;
}

.request-item:hover {
  background: var(--bg-secondary);
}

.request-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.request-avatar {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 600;
}

.request-name {
  font-weight: 500;
  color: var(--text-primary);
}

.request-username {
  font-size: 12px;
  color: var(--text-muted);
}

.request-actions {
  display: flex;
  gap: 8px;
}

.empty-center {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 20px;
}

:deep(.el-dropdown-menu__item) {
  color: var(--text-primary);
}

:deep(.el-dropdown-menu__item:hover) {
  background: var(--bg-hover);
}
</style>
