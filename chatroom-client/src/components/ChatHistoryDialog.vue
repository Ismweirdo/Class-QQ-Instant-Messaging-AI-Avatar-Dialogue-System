<template>
  <el-dialog v-model="visible" :title="`聊天记录 - ${targetName}`" width="600px" top="5vh">
    <div class="history-search">
      <el-input v-model="keyword" placeholder="搜索聊天记录" size="small" clearable />
    </div>
    <div class="history-list" v-loading="loading">
      <div v-if="messages.length === 0 && !loading" class="empty">暂无聊天记录</div>
      <div v-for="msg in filteredMessages" :key="msg.id" class="history-item"
        :class="{ mine: msg.senderId === userId }">
        <div class="msg-header">
          <span class="msg-sender">{{ msg.senderName }}</span>
          <span class="msg-time">{{ formatTime(msg.createdAt) }}</span>
        </div>
        <div class="msg-content">{{ msg.content }}</div>
        <div v-if="msg.replyToContent" class="msg-reply">回复: {{ msg.replyToContent }}</div>
      </div>
    </div>
    <div class="history-pager" v-if="total > pageSize">
      <el-pagination v-model:current-page="page" :page-size="pageSize"
        :total="total" layout="prev, next" small @current-change="load" />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getChatHistory } from '../api/message'
import { useUserStore } from '../store/user'

const props = defineProps({ visible: Boolean, targetId: Number, targetName: String })
const emit = defineEmits(['update:visible'])
const visible = computed({ get: () => props.visible, set: v => emit('update:visible', v) })

const userStore = useUserStore()
const userId = computed(() => userStore.userId)
const messages = ref([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const pageSize = 20

const filteredMessages = computed(() => {
  if (!keyword.value) return messages.value
  const kw = keyword.value.toLowerCase()
  return messages.value.filter(m => m.content.toLowerCase().includes(kw) ||
    (m.senderName && m.senderName.toLowerCase().includes(kw)))
})

async function load() {
  loading.value = true
  try {
    const data = await getChatHistory(props.targetId, page.value, pageSize)
    messages.value = data.records || data || []
    total.value = data.total || 0
  } catch { messages.value = [] }
  finally { loading.value = false }
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return `${d.getMonth()+1}/${d.getDate()} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

watch(visible, v => { if (v) { page.value = 1; load() } })
</script>

<style scoped>
.history-search { margin-bottom: 12px; }
.history-list { max-height: 50vh; overflow-y: auto; }
.history-item { padding: 10px; margin: 4px 0; border-radius: 8px; background: var(--bg-secondary); }
.history-item.mine { background: linear-gradient(135deg, rgba(99,102,241,0.08), rgba(139,92,246,0.05)); }
.msg-header { display: flex; justify-content: space-between; margin-bottom: 4px; }
.msg-sender { font-size: 12px; color: var(--primary-color); font-weight: 600; }
.msg-time { font-size: 11px; color: var(--text-muted); }
.msg-content { font-size: 13px; color: var(--text-primary); word-break: break-word; }
.msg-reply { font-size: 11px; color: var(--text-muted); margin-top: 4px; padding-left: 8px; border-left: 2px solid var(--border-color); }
.empty { text-align: center; padding: 40px; color: var(--text-muted); }
.history-pager { margin-top: 12px; display: flex; justify-content: center; }
</style>
