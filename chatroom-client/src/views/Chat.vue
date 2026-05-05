<template>
  <div class="chat-layout">
    <!-- Left Sidebar -->
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="user-info">
          <span class="nickname">{{ userStore.nickname }}</span>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="el-dropdown-link">
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="header-actions">
          <el-badge :value="contactStore.pendingRequests.length" :hidden="!contactStore.hasPendingRequests">
            <el-button size="small" @click="openFriendRequests" circle>
              <el-icon><UserFilled /></el-icon>
            </el-button>
          </el-badge>
          <el-button size="small" @click="showAddFriend = true" circle>
            <el-icon><Plus /></el-icon>
          </el-button>
          <el-button size="small" @click="showCreateGroup = true" circle>
            <el-icon><Message /></el-icon>
          </el-button>
        </div>
      </div>
      <ContactList @select="onSelectContact" @group-info="onGroupInfo" />
    </div>

    <!-- Right Chat Area -->
    <div class="chat-area">
      <ChatWindow v-if="activeChat" :key="activeChat.key"
        :type="activeChat.type"
        :targetId="activeChat.id"
        :targetName="activeChat.name"
        @back="activeChat = null" />
      <div v-else class="no-chat">
        <el-icon :size="80" color="#ddd"><ChatDotRound /></el-icon>
        <p>选择一个联系人开始聊天</p>
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
        <span>{{ req.nickname }} ({{ req.username }})</span>
        <div>
          <el-button type="primary" size="small" @click="handleAccept(req.friendId)">接受</el-button>
          <el-button size="small" @click="handleReject(req.friendId)">拒绝</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- Group Info Dialog -->
    <GroupInfoDialog v-model:visible="showGroupInfo" :group="selectedGroup" @refresh="refreshContacts" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { useContactStore } from '../store/contact'
import { useChatStore } from '../store/chat'
import { connectWebSocket, disconnectWebSocket, addMessageHandler, removeMessageHandler, addPresenceHandler, removePresenceHandler, subscribeGroupMessages } from '../utils/websocket'
import { acceptFriendRequest, rejectFriendRequest } from '../api/friend'
import { ElMessage } from 'element-plus'
import ContactList from '../components/ContactList.vue'
import ChatWindow from '../components/ChatWindow.vue'
import AddFriendDialog from '../components/AddFriendDialog.vue'
import CreateGroupDialog from '../components/CreateGroupDialog.vue'
import GroupInfoDialog from '../components/GroupInfoDialog.vue'

const router = useRouter()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()

const showAddFriend = ref(false)
const showCreateGroup = ref(false)
const showFriendRequests = ref(false)
const showGroupInfo = ref(false)
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
  if (command === 'logout') {
    disconnectWebSocket()
    userStore.logout()
    router.push('/login')
  }
}

async function subscribeAllGroups() {
  const groups = contactStore.groupList
  for (const g of groups) {
    subscribeGroupMessages(g.id)
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
.chat-layout { display: flex; height: 100vh; }
.sidebar { width: 320px; background: #f5f5f5; display: flex; flex-direction: column; border-right: 1px solid #e0e0e0; }
.sidebar-header { padding: 16px; background: #fff; border-bottom: 1px solid #e0e0e0; }
.user-info { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.nickname { font-size: 18px; font-weight: 600; }
.header-actions { display: flex; gap: 8px; }
.chat-area { flex: 1; display: flex; flex-direction: column; }
.no-chat { flex: 1; display: flex; flex-direction: column; justify-content: center; align-items: center; color: #bbb; gap: 16px; font-size: 16px; }
.request-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid #eee; }
.empty-center { text-align: center; color: #999; padding: 20px; }
</style>
