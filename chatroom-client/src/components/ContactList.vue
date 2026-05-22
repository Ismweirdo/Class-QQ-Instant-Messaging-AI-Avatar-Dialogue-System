<template>
  <div class="contact-list">
    <!-- Search -->
    <div class="search-box">
      <el-input v-model="search" placeholder="搜索好友或群聊" prefix-icon="Search" size="small" clearable />
    </div>

    <!-- Toolbar -->
    <div class="contact-toolbar">
      <template v-if="!batchMode">
        <el-button text size="small" @click="enterBatchMode">批量管理</el-button>
      </template>
      <template v-else>
        <span class="batch-hint">已选择 {{ selectedIds.length }} 项</span>
        <el-button text size="small" type="danger" :disabled="selectedIds.length === 0" :loading="deleting" @click="handleBatchDelete">删除</el-button>
        <el-button text size="small" @click="exitBatchMode">取消</el-button>
      </template>
    </div>

    <!-- Tabs -->
    <el-tabs v-model="activeTab" class="contact-tabs">
      <el-tab-pane label="机器人" name="bots">
        <div v-if="botFriends.length === 0" class="empty-hint">
          <div class="empty-hint-icon">
            <el-icon :size="28" color="var(--text-muted)"><User /></el-icon>
          </div>
          <div class="empty-hint-title">暂无机器人</div>
          <div class="empty-hint-desc">导入聊天记录生成机器人</div>
        </div>
        <template v-for="bot in botFriends" :key="bot.friendId">
          <el-dropdown v-if="!batchMode" trigger="contextmenu" :hide-on-click="false">
            <div class="contact-item" :class="{ active: isActive('private', bot.friendId) }"
              @click="selectContact('private', bot.friendId, displayName(bot))">
              <div class="avatar-wrapper">
                <el-avatar class="contact-avatar" :src="bot.avatar || undefined">{{ displayName(bot)[0] }}</el-avatar>
              </div>
              <div class="contact-info">
                <div class="contact-name">{{ displayName(bot) }}</div>
                <div class="contact-status">AI 机器人</div>
              </div>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleSetRemark(bot)">设置备注</el-dropdown-item>
                <el-dropdown-item @click="handleDeleteBot(bot)">彻底删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <div v-else class="contact-item batch-item" :class="{ 'batch-selected': selectedIds.includes(bot.friendId) }"
            @click="toggleSelect(bot.friendId)">
            <el-checkbox :model-value="selectedIds.includes(bot.friendId)" @click.stop />
            <div class="avatar-wrapper">
              <el-avatar class="contact-avatar" :src="bot.avatar || undefined">{{ displayName(bot)[0] }}</el-avatar>
            </div>
            <div class="contact-info">
              <div class="contact-name">{{ displayName(bot) }}</div>
              <div class="contact-status">AI 机器人</div>
            </div>
          </div>
        </template>
      </el-tab-pane>

      <el-tab-pane label="群聊" name="groups">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useContactStore } from '../store/contact'
import { deleteFriend, setRemark } from '../api/friend'
import { deleteBot } from '../api/bot'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, Message } from '@element-plus/icons-vue'

const emit = defineEmits(['select', 'groupInfo', 'refresh'])
const contactStore = useContactStore()
const search = ref('')
const activeTab = ref('bots')

const batchMode = ref(false)
const selectedIds = ref([])
const deleting = ref(false)

function displayName(friend) {
  return friend.remark || friend.nickname || friend.username
}

// Filter friends by tab
const normalFriends = computed(() => {
  let list = contactStore.friendList.filter(f => f.isBot !== 1)
  if (search.value) {
    const kw = search.value.toLowerCase()
    list = list.filter(f =>
      displayName(f).toLowerCase().includes(kw) ||
      (f.remark && f.remark.toLowerCase().includes(kw)))
  }
  return list
})

const botFriends = computed(() => {
  let list = contactStore.friendList.filter(f => f.isBot === 1)
  if (search.value) {
    const kw = search.value.toLowerCase()
    list = list.filter(f =>
      displayName(f).toLowerCase().includes(kw) ||
      (f.remark && f.remark.toLowerCase().includes(kw)))
  }
  return list
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

// --- Batch mode ---

function enterBatchMode() {
  batchMode.value = true
  selectedIds.value = []
}

function exitBatchMode() {
  batchMode.value = false
  selectedIds.value = []
}

function toggleSelect(friendId) {
  const idx = selectedIds.value.indexOf(friendId)
  if (idx > -1) {
    selectedIds.value.splice(idx, 1)
  } else {
    selectedIds.value.push(friendId)
  }
}

// --- Single friend delete (remove relationship only) ---

async function handleSetRemark(friend) {
  try {
    const { value } = await ElMessageBox.prompt(
      `为 "${displayName(friend)}" 设置备注`,
      '设置备注',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: friend.remark || '',
        inputPlaceholder: '输入备注名称（留空则清除备注）'
      }
    )
    if (value !== undefined && value !== null) {
      const remark = value.trim()
      await setRemark(friend.friendId, remark)
      friend.remark = remark || null
      ElMessage.success(remark ? '备注已保存' : '备注已清除')
      // Update active contact name if this friend is currently active
      const active = contactStore.activeContact
      if (active && active.type === 'private' && active.id === friend.friendId) {
        contactStore.setActiveContact({ ...active, name: displayName(friend) })
      }
    }
  } catch {
    // User cancelled
  }
}

async function handleDeleteFriend(friend) {
  try {
    await ElMessageBox.confirm(
      `确定要删除好友 "${displayName(friend)}" 吗？`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deleteFriend(friend.friendId)
    ElMessage.success('好友已删除')
    clearActiveIfNeeded(friend.friendId)
    emit('refresh')
  } catch {
    // User cancelled or API error handled by interceptor
  }
}

// --- Single bot delete (permanent) ---

async function handleDeleteBot(bot) {
  try {
    await ElMessageBox.confirm(
      `确定要彻底删除机器人 "${displayName(bot)}" 吗？此操作不可恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
    await deleteBot(bot.friendId)
    ElMessage.success('机器人已彻底删除')
    clearActiveIfNeeded(bot.friendId)
    emit('refresh')
  } catch {
    // User cancelled or API error handled by interceptor
  }
}

function clearActiveIfNeeded(friendId) {
  const active = contactStore.activeContact
  if (active && active.type === 'private' && active.id === friendId) {
    contactStore.setActiveContact(null)
    emit('select', null)
  }
}

// --- Batch delete ---

async function handleBatchDelete() {
  if (selectedIds.value.length === 0) return
  // Determine which tab to use the right delete function
  const isBotTab = activeTab.value === 'bots'
  const label = isBotTab ? '机器人' : '好友'

  try {
    await ElMessageBox.confirm(
      `确定要${isBotTab ? '彻底' : ''}删除选中的 ${selectedIds.value.length} 个${label}吗？${isBotTab ? '此操作不可恢复。' : ''}`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    deleting.value = true
    const deleteFn = isBotTab ? deleteBot : deleteFriend
    const results = await Promise.allSettled(
      selectedIds.value.map(id => deleteFn(id))
    )
    const succeeded = results.filter(r => r.status === 'fulfilled').length
    const failed = results.filter(r => r.status === 'rejected').length

    const active = contactStore.activeContact
    if (active && active.type === 'private' && selectedIds.value.includes(active.id)) {
      contactStore.setActiveContact(null)
      emit('select', null)
    }

    if (failed === 0) {
      ElMessage.success(`成功删除 ${succeeded} 个${label}`)
    } else {
      ElMessage.warning(`成功删除 ${succeeded} 个${label}，${failed} 个失败`)
    }
    exitBatchMode()
    emit('refresh')
  } catch {
    // User cancelled
  } finally {
    deleting.value = false
  }
}

// Exit batch mode when switching tabs
watch(activeTab, () => {
  if (batchMode.value) exitBatchMode()
})
</script>

<style scoped>
.contact-list {
  flex: 1;
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

.contact-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 16px 4px;
  gap: 8px;
}

.batch-hint {
  font-size: 13px;
  color: var(--text-muted);
  margin-right: auto;
}

.contact-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
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
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.contact-tabs :deep(.el-tab-pane) {
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

.batch-item.batch-selected {
  background: linear-gradient(90deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.05));
  border-left: 3px solid var(--primary-color);
}

.batch-item .el-checkbox {
  margin-right: 0;
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

/* Scrollbar styling */
.contact-tabs :deep(.el-tabs__content)::-webkit-scrollbar {
  width: 5px;
}

.contact-tabs :deep(.el-tabs__content)::-webkit-scrollbar-track {
  background: transparent;
}

.contact-tabs :deep(.el-tabs__content)::-webkit-scrollbar-thumb {
  background: var(--border-light);
  border-radius: 10px;
}

.contact-tabs :deep(.el-tabs__content)::-webkit-scrollbar-thumb:hover {
  background: var(--text-muted);
}
</style>
