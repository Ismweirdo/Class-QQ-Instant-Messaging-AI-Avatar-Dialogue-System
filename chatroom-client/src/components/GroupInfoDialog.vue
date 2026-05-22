<template>
  <el-dialog v-model="visible" title="群聊信息" width="520px" @open="loadGroupDetail">
    <template v-if="groupDetail">
      <div class="group-header">
        <el-avatar :size="56">{{ groupDetail.name?.[0] }}</el-avatar>
        <div class="group-title">
          <div class="group-name">{{ groupDetail.name }}</div>
          <div class="group-count">{{ groupDetail.memberCount }} 名成员</div>
        </div>
      </div>

      <el-divider />

      <div class="section-title">
        群成员
        <el-button type="primary" text size="small" @click="showInvite = true">
          <el-icon><Plus /></el-icon> 邀请
        </el-button>
      </div>

      <div class="member-list">
        <div v-for="m in groupDetail.members" :key="m.userId" class="member-item">
          <el-avatar :size="32">{{ (m.nickname || m.username)?.[0] }}</el-avatar>
          <span class="member-name">{{ m.nicknameInGroup || m.nickname || m.username }}</span>
          <el-tag v-if="m.role === 2" size="small" type="danger">群主</el-tag>
          <el-tag v-else-if="m.role === 1" size="small" type="warning">管理员</el-tag>
          <div class="member-actions" v-if="m.role < 2 && isOwner">
            <el-button type="danger" text size="small" @click="handleRemoveMember(m.userId)">移除</el-button>
          </div>
        </div>
      </div>

      <!-- Invite dialog -->
      <el-dialog v-model="showInvite" title="邀请成员" width="400px" append-to-body>
        <el-select v-model="inviteIds" multiple filterable placeholder="选择好友"
          style="width:100%">
          <el-option v-for="f in availableFriends" :key="f.friendId"
            :label="f.nickname || f.username" :value="f.friendId" />
        </el-select>
        <template #footer>
          <el-button @click="showInvite = false">取消</el-button>
          <el-button type="primary" @click="handleInvite" :loading="inviting" :disabled="inviteIds.length === 0">
            邀请
          </el-button>
        </template>
      </el-dialog>

      <el-divider />

      <div class="group-actions">
        <el-button v-if="isOwner" type="danger" @click="handleDisband" :loading="disbanding">
          解散群聊
        </el-button>
        <el-button v-else type="danger" plain @click="handleQuit">
          退出群聊
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useContactStore } from '../store/contact'
import { useUserStore } from '../store/user'
import { getGroupDetail, inviteMember, removeMember, quitGroup, disbandGroup } from '../api/group'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({
  visible: Boolean,
  group: Object
})
const emit = defineEmits(['update:visible', 'refresh'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const contactStore = useContactStore()
const userStore = useUserStore()
const groupDetail = ref(null)
const showInvite = ref(false)
const inviteIds = ref([])
const inviting = ref(false)
const disbanding = ref(false)

const isOwner = computed(() => {
  if (!groupDetail.value || !userStore.user) return false
  return groupDetail.value.ownerId === userStore.user.id
})

const availableFriends = computed(() => {
  if (!groupDetail.value) return []
  const memberIds = new Set(groupDetail.value.members.map(m => m.userId))
  return contactStore.friendList.filter(f => !memberIds.has(f.friendId))
})

async function loadGroupDetail() {
  if (!props.group?.id) return
  try {
    groupDetail.value = await getGroupDetail(props.group.id)
  } catch (e) {
    ElMessage.error('加载群信息失败')
  }
}

async function handleInvite() {
  if (inviteIds.value.length === 0) return
  inviting.value = true
  try {
    for (const uid of inviteIds.value) {
      await inviteMember(props.group.id, uid)
    }
    ElMessage.success('邀请成功')
    showInvite.value = false
    inviteIds.value = []
    await loadGroupDetail()
    emit('refresh')
  } catch (e) {
    // handled
  } finally {
    inviting.value = false
  }
}

async function handleRemoveMember(userId) {
  try {
    await ElMessageBox.confirm('确定要移除该成员吗？', '确认', { type: 'warning' })
    await removeMember(props.group.id, userId)
    ElMessage.success('已移除')
    await loadGroupDetail()
    emit('refresh')
  } catch (e) {
    // cancel or error
  }
}

async function handleQuit() {
  try {
    await ElMessageBox.confirm('确定要退出该群聊吗？', '确认', { type: 'warning' })
    await quitGroup(props.group.id)
    ElMessage.success('已退出群聊')
    visible.value = false
    emit('refresh')
  } catch (e) {
    // cancel or error
  }
}

async function handleDisband() {
  try {
    await ElMessageBox.confirm(
      '解散后所有群成员将被移除，聊天记录将被清空，此操作不可恢复。确定要解散该群聊吗？',
      '解散群聊',
      { type: 'error', confirmButtonText: '确定解散', cancelButtonText: '取消' }
    )
    disbanding.value = true
    await disbandGroup(props.group.id)
    ElMessage.success('群聊已解散')
    visible.value = false
    emit('refresh')
  } catch (e) {
    // cancel or error
  } finally {
    disbanding.value = false
  }
}
</script>

<style scoped>
.group-header { display: flex; align-items: center; gap: 16px; }
.group-title { display: flex; flex-direction: column; gap: 4px; }
.group-name { font-size: 18px; font-weight: 600; }
.group-count { font-size: 13px; color: #999; }
.section-title { display: flex; justify-content: space-between; align-items: center; font-weight: 600; margin-bottom: 12px; }
.member-list { max-height: 300px; overflow-y: auto; }
.member-item { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #f5f5f5; }
.member-name { flex: 1; font-size: 14px; }
.member-actions { margin-left: auto; }
.group-actions { text-align: center; }
</style>
