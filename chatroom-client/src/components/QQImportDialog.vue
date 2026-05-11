<template>
  <el-dialog v-model="visible" title="从QQ导入聊天记录" width="650px" @open="onOpen">
    <!-- Step 0: QQCE not connected -->
    <div v-if="!qqConnected && !checking">
      <el-alert type="warning" :closable="false" style="margin-bottom:16px">
        <template #title>未检测到 QQ Chat Exporter</template>
        请先启动 QQ Chat Exporter 并扫码登录 QQ。
      </el-alert>
      <div style="background:#f8f9fa;padding:12px;border-radius:6px;font-size:13px;line-height:1.8;">
        <p><b>1.</b> 下载 QQ Chat Exporter: <a href="https://github.com/shuakami/qq-chat-exporter/releases" target="_blank">Releases</a></p>
        <p><b>2.</b> 解压并运行 <code>launcher-user.bat</code> (Windows)</p>
        <p><b>3.</b> 用 QQ 扫码登录</p>
        <p><b>4.</b> 确保 <code>http://localhost:40653</code> 可访问</p>
      </div>
      <div style="text-align:center;margin-top:16px;">
        <el-button type="primary" @click="checkQQCE" :loading="checking">重新检测连接</el-button>
      </div>
    </div>

    <!-- Step 1: Select contacts -->
    <div v-else-if="!importing && importResults.length === 0">
      <el-alert type="success" :closable="false" style="margin-bottom:12px">
        已连接 QQ Chat Exporter，共 {{ friends.length }} 个好友，{{ groups.length }} 个群
      </el-alert>

      <el-tabs v-model="qqTab">
        <el-tab-pane :label="`好友 (${friends.length})`" name="friends">
          <el-input v-model="friendFilter" placeholder="搜索好友..." size="small" clearable style="margin-bottom:8px" />
          <div class="qq-contact-list">
            <div v-for="f in filteredFriends" :key="f.uid" class="qq-contact-item"
                 :class="{selected: isSelected('friend', f.uid)}"
                 @click="toggleSelect('friend', f.uid, f.nick || f.remark || f.uin, f.avatarUrl)">
              <el-avatar :size="32" :src="f.avatarUrl" />
              <span class="qq-contact-name">{{ f.remark || f.nick || f.uin }}</span>
              <el-checkbox :model-value="isSelected('friend', f.uid)" />
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`群聊 (${groups.length})`" name="groups">
          <el-input v-model="groupFilter" placeholder="搜索群聊..." size="small" clearable style="margin-bottom:8px" />
          <div class="qq-contact-list">
            <div v-for="g in filteredGroups" :key="g.groupCode" class="qq-contact-item"
                 :class="{selected: isSelected('group', g.groupCode)}"
                 @click="toggleSelect('group', g.groupCode, g.groupName, g.avatarUrl)">
              <el-avatar :size="32" :src="g.avatarUrl" />
              <span class="qq-contact-name">{{ g.groupName }}</span>
              <span style="font-size:11px;color:#999;">{{ g.memberCount }}人</span>
              <el-checkbox :model-value="isSelected('group', g.groupCode)" />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div style="display:flex;justify-content:space-between;align-items:center;margin-top:12px;">
        <span style="font-size:13px;color:#666;">已选 {{ selections.length }} 个</span>
        <div>
          <span style="font-size:13px;color:#999;margin-right:8px;">每人获取</span>
          <el-input-number v-model="msgCount" :min="100" :max="5000" :step="100" size="small" style="width:120px" />
          <span style="font-size:13px;color:#999;margin-left:4px;">条消息</span>
        </div>
      </div>

      <div style="text-align:center;margin-top:16px;">
        <el-button type="primary" size="large" @click="doQQImport" :disabled="selections.length === 0" :loading="importing">
          导入选中联系人的聊天记录并生成机器人
        </el-button>
      </div>
    </div>

    <!-- Step 2: Importing -->
    <div v-else-if="importing" style="text-align:center;padding:40px;">
      <el-icon :size="48" color="#409EFF" class="is-loading"><Loading /></el-icon>
      <p style="margin-top:16px;color:#666;">正在从 QQ 获取聊天记录并生成机器人...</p>
      <p style="font-size:12px;color:#999;">这可能需要几分钟，请耐心等待</p>
    </div>

    <!-- Step 3: Result -->
    <div v-else>
      <el-alert
        :type="totalGenerated > 0 ? 'success' : 'warning'"
        :closable="false"
        style="margin-bottom:12px"
      >
        <template #title>
          {{ totalGenerated > 0 ? `已生成 ${totalGenerated} 个机器人` : '未生成机器人' }}
        </template>
        {{ totalGenerated > 0 ? '以下为每个联系人/群聊的导入诊断结果。' : '请根据下面诊断结果调整导入范围或消息条数后重试。' }}
      </el-alert>

      <div class="diag-list">
        <div v-for="(r, idx) in importResults" :key="idx" class="diag-item">
          <div class="diag-title">
            <strong>{{ r.selectionName || '-' }}</strong>
            <el-tag size="small" :type="r.status === 'generated' ? 'success' : (r.status === 'error' ? 'danger' : 'warning')">
              {{ r.status }}
            </el-tag>
          </div>
          <div class="diag-line">类型: {{ r.selectionType }} | ID: {{ r.selectionId }}</div>
          <div class="diag-line">rawMessageCount: {{ r.rawMessageCount }} | simpleMessageCount: {{ r.simpleMessageCount }} | generatedCount: {{ r.generatedCount }}</div>
          <div class="diag-line">message: {{ r.message }}</div>
          <div class="diag-line">senderStats: {{ formatSenderStats(r.senderStats) }}</div>
          <div v-if="r.rawPreview && r.rawPreview.length" class="diag-raw">
            <div class="diag-line"><b>rawPreview（前{{ r.rawPreview.length }}条结构摘要）</b></div>
            <div v-for="(p, pIdx) in r.rawPreview" :key="pIdx" class="diag-raw-item">
              <div class="diag-line">#{{ p.index }} topLevelKeys: {{ formatKeys(p.topLevelKeys) }}</div>
              <div class="diag-line">senderHints: {{ formatObj(p.senderHints) }}</div>
              <div class="diag-line">contentHints: {{ formatObj(p.contentHints) }}</div>
            </div>
          </div>
        </div>
      </div>

      <div style="text-align:center;margin-top:16px;">
        <el-button @click="resetResult">继续导入</el-button>
        <el-button type="primary" @click="closeDialog">完成</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { checkQQCEHealth, getQQFriends, getQQGroups, qqImportBots } from '../api/bot'
import { ElMessage } from 'element-plus'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible', 'done'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const checking = ref(false)
const qqConnected = ref(false)
const friends = ref([])
const groups = ref([])
const selections = ref([])
const msgCount = ref(500)
const qqTab = ref('friends')
const friendFilter = ref('')
const groupFilter = ref('')
const importing = ref(false)
const importResults = ref([])
const totalGenerated = computed(() =>
  importResults.value.reduce((sum, item) => sum + Number(item.generatedCount || 0), 0))

const filteredFriends = computed(() => {
  if (!friendFilter.value) return friends.value
  const kw = friendFilter.value.toLowerCase()
  return friends.value.filter(f =>
    (f.nick || '').toLowerCase().includes(kw) ||
    (f.remark || '').toLowerCase().includes(kw) ||
    String(f.uin || '').includes(kw))
})

const filteredGroups = computed(() => {
  if (!groupFilter.value) return groups.value
  const kw = groupFilter.value.toLowerCase()
  return groups.value.filter(g =>
    (g.groupName || '').toLowerCase().includes(kw) ||
    String(g.groupCode || '').includes(kw))
})

async function onOpen() {
  checking.value = true
  qqConnected.value = false
  friends.value = []
  groups.value = []
  selections.value = []
  importing.value = false
  importResults.value = []
  msgCount.value = 500
  friendFilter.value = ''
  groupFilter.value = ''
  await checkQQCE()
}

async function checkQQCE() {
  checking.value = true
  try {
    const data = await checkQQCEHealth()
    if (data.connected) {
      qqConnected.value = true
      // Load friends and groups
      const [f, g] = await Promise.all([getQQFriends(), getQQGroups()])
      friends.value = f || []
      groups.value = g || []
      ElMessage.success(`已连接，找到 ${friends.value.length} 个好友，${groups.value.length} 个群`)
    } else {
      qqConnected.value = false
    }
  } catch (e) {
    qqConnected.value = false
  } finally {
    checking.value = false
  }
}

function isSelected(chatType, peerUid) {
  return selections.value.some(s => s.chatType === chatType && s.peerUid === peerUid)
}

function toggleSelect(chatType, peerUid, name, avatarUrl) {
  const idx = selections.value.findIndex(s => s.chatType === chatType && s.peerUid === peerUid)
  if (idx >= 0) {
    selections.value.splice(idx, 1)
  } else {
    selections.value.push({ chatType, peerUid, name, avatarUrl })
  }
}

async function doQQImport() {
  if (selections.value.length === 0) return
  importing.value = true
  try {
    const res = await qqImportBots({
      selections: selections.value,
      messageCount: msgCount.value
    })
    importResults.value = Array.isArray(res) ? res : []
    if (totalGenerated.value > 0) {
      ElMessage.success(`QQ导入完成，已生成 ${totalGenerated.value} 个机器人`)
      emit('done')
    } else {
      ElMessage.warning('QQ导入完成，但未生成机器人，请查看诊断结果')
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.message || '未知错误'))
  } finally {
    importing.value = false
  }
}

function formatSenderStats(stats) {
  if (!stats || typeof stats !== 'object' || Array.isArray(stats)) return '-'
  const entries = Object.entries(stats)
  if (entries.length === 0) return '{}'
  return entries.map(([k, v]) => `${k}: ${v}`).join(', ')
}

function formatKeys(keys) {
  if (!Array.isArray(keys)) return '-'
  return keys.join(', ')
}

function formatObj(obj) {
  if (!obj || typeof obj !== 'object') return '-'
  try {
    return JSON.stringify(obj)
  } catch {
    return String(obj)
  }
}

function resetResult() {
  importResults.value = []
}

function closeDialog() {
  visible.value = false
}
</script>

<style scoped>
.qq-contact-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 6px;
}
.qq-contact-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}
.qq-contact-item:hover { background: #f0f7ff; }
.qq-contact-item.selected { background: #e6f4ff; }
.qq-contact-item:last-child { border-bottom: none; }
.qq-contact-name { flex: 1; font-size: 14px; }

.diag-list {
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 6px;
}
.diag-item {
  padding: 10px 12px;
  border-bottom: 1px solid #f5f5f5;
}
.diag-item:last-child { border-bottom: none; }
.diag-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.diag-line {
  font-size: 12px;
  color: #666;
  line-height: 1.6;
  word-break: break-all;
}

.diag-raw {
  margin-top: 6px;
  padding: 6px 8px;
  background: #fafafa;
  border: 1px dashed #e5e7eb;
  border-radius: 4px;
}

.diag-raw-item {
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px dotted #eee;
}
.diag-raw-item:first-child {
  border-top: none;
  padding-top: 0;
}
</style>
