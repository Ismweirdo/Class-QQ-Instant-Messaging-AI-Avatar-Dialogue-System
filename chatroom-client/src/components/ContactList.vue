<template>
  <div class="contact-list">
    <!-- Search -->
    <div class="search-box">
      <el-input v-model="search" placeholder="搜索好友或群聊" prefix-icon="Search" size="small" clearable />
    </div>

    <!-- Tabs -->
    <el-tabs v-model="activeTab" class="contact-tabs">
      <el-tab-pane label="好友" name="friends">
        <div class="contact-pane">
          <div v-if="contactStore.friendList.length === 0" class="empty-hint">
            <div class="empty-hint-icon">
              <el-icon :size="28" color="var(--text-muted)"><User /></el-icon>
            </div>
            <div class="empty-hint-title">暂无好友</div>
            <div class="empty-hint-desc">点击 + 按钮添加好友</div>
          </div>
          <div v-for="friend in filteredFriends" :key="friend.friendId"
            class="contact-item" :class="{ active: isActive('private', friend.friendId) }"
            @click="selectContact('private', friend.friendId, friend.nickname || friend.username)">
            <div class="avatar-wrapper">
              <el-avatar class="contact-avatar">{{ (friend.nickname || friend.username)[0] }}</el-avatar>
              <div class="status-badge" :class="{ online: friend.status === 1, offline: friend.status !== 1 }"></div>
            </div>
            <div class="contact-info">
              <div class="contact-name">{{ friend.nickname || friend.username }}</div>
              <div class="contact-status">
                <span v-if="friend.status === 1" style="color: #22c55e">在线</span>
                <span v-else>离线</span>
              </div>
            </div>
            <el-popconfirm
              title="确认删除该好友吗？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleDeleteFriend(friend)"
            >
              <template #reference>
                <button class="delete-friend-btn" @click.stop aria-label="删除好友">
                  删除
                </button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="群聊" name="groups">
        <div class="contact-pane">
          <div v-if="filteredGroups.length === 0 && !search" class="empty-hint">
            <div class="empty-hint-icon">
              <el-icon :size="28" color="var(--text-muted)"><Message /></el-icon>
            </div>
            <div class="empty-hint-title">暂无群聊</div>
            <div class="empty-hint-desc">点击消息图标创建群聊</div>
          </div>
          <div v-for="group in filteredGroups" :key="group.id"
            class="contact-item" :class="{ active: isActive('group', group.id) }"
            @click="selectContact('group', group.id, group.name)"
            @dblclick="emit('groupInfo', group)">
            <div class="avatar-wrapper">
              <el-avatar class="contact-avatar" style="background: linear-gradient(135deg, #f59e0b, #ef4444);">
                {{ group.name[0] }}
              </el-avatar>
            </div>
            <div class="contact-info">
              <div class="contact-name">{{ group.name }}</div>
              <div class="contact-status">
                <span class="group-icon">{{ group.memberCount }}</span>
                <span>{{ group.memberCount }} 位成员</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useContactStore } from '../store/contact'
import { deleteFriend } from '../api/friend'
import { ElMessage } from 'element-plus'
import { User, Message } from '@element-plus/icons-vue'

const emit = defineEmits(['select', 'groupInfo', 'friendDeleted'])
const contactStore = useContactStore()
const search = ref('')
const activeTab = ref('friends')

const filteredFriends = computed(() => {
  if (!search.value) return contactStore.friendList
  const kw = search.value.toLowerCase()
  return contactStore.friendList.filter(f =>
    (f.nickname || f.username).toLowerCase().includes(kw)
  )
})

const filteredGroups = computed(() => {
  if (!search.value) return contactStore.groupList
  const kw = search.value.toLowerCase()
  return contactStore.groupList.filter(g => g.name.toLowerCase().includes(kw))
})

function isActive(type, id) {
  const contact = contactStore.activeContact
  return contact && contact.type === type && contact.id === id
}

function selectContact(type, id, name) {
  const contact = { type, id, name, key: `${type}_${id}` }
  contactStore.setActiveContact(contact)
  emit('select', contact)
}

async function handleDeleteFriend(friend) {
  try {
    await deleteFriend(friend.friendId)
    ElMessage.success('好友已删除')
    await contactStore.fetchFriends()
    emit('friendDeleted', friend.friendId)
  } catch (e) {
    ElMessage.error(e?.message || '删除好友失败')
  }
}
</script>

<style scoped>
.contact-list {
  height: 100%;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--bg-primary);
}

.search-box {
  padding: 16px;
  border-bottom: 1px solid var(--border-light);
}

.search-box :deep(.el-input__wrapper) {
  border-radius: 12px;
  background: var(--bg-secondary);
  border: none;
}

.search-box :deep(.el-input__inner) {
  background: transparent;
}

.contact-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.contact-tabs :deep(.el-tabs__header) {
  flex-shrink: 0;
  padding: 0 16px;
  margin-bottom: 0;
}

.contact-tabs :deep(.el-tabs__nav) {
  padding: 12px 0;
}

.contact-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}

.contact-tabs :deep(.el-tab-pane) {
  height: 100%;
  min-height: 0;
}

.contact-pane {
  height: 100%;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.contact-item {
  display: flex;
  align-items: center;
  padding: 14px 16px;
  cursor: pointer;
  gap: 14px;
  transition: all 0.25s ease;
  border-radius: 0 16px 16px 0;
  margin-right: 8px;
}

.contact-item:hover {
  background: var(--bg-secondary);
  transform: translateX(4px);
}

.contact-item.active {
  background: linear-gradient(90deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.05));
  border-left: 3px solid var(--primary-color);
}

.avatar-wrapper {
  position: relative;
  flex-shrink: 0;
}

.contact-avatar {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  font-size: 16px;
  font-weight: 600;
}

.status-badge {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid var(--bg-primary);
  transition: all 0.3s ease;
}

.status-badge.online {
  background: #22c55e;
  box-shadow: 0 0 8px rgba(34, 197, 94, 0.5);
}

.status-badge.offline {
  background: #94a3b8;
}

.contact-info {
  flex: 1;
  min-width: 0;
}

.contact-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.contact-status {
  font-size: 13px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}

.delete-friend-btn {
  border: none;
  background: transparent;
  color: #ef4444;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 8px;
}

.delete-friend-btn:hover {
  background: rgba(239, 68, 68, 0.08);
}

.group-icon {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: white;
  font-weight: 600;
}

.empty-hint {
  text-align: center;
  color: var(--text-muted);
  padding: 60px 20px;
  font-size: 14px;
}

.empty-hint-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background: var(--bg-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-hint-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.empty-hint-desc {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
