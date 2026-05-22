<template>
  <div class="provider-selector">
    <div class="avatar-row">
      <el-avatar :src="botAvatar" :size="40" style="cursor:pointer" @click="$refs.avatarInput.click()">
        <el-icon><Camera /></el-icon>
      </el-avatar>
      <input ref="avatarInput" type="file" accept="image/*" style="display:none" @change="onAvatarChange" />
      <span style="font-size:12px;color:var(--text-muted)">点击更换头像</span>
    </div>
    <div class="provider-row">
      <el-select
        v-model="selectedProvider"
        placeholder="选择AI厂商"
        size="small"
        style="width:130px"
        @change="onProviderChange"
      >
        <el-option
          v-for="p in providers"
          :key="p.id"
          :label="p.name"
          :value="p.id"
        />
      </el-select>

      <el-select
        v-model="selectedModel"
        placeholder="模型"
        size="small"
        style="width:140px"
        filterable
        allow-create
        @change="onModelChange"
      >
        <el-option
          v-for="m in availableModels"
          :key="m"
          :label="m"
          :value="m"
        />
      </el-select>

      <el-button
        size="small"
        @click="showKeyDialog = true"
        :type="apiKeyConfigured ? 'success' : 'warning'"
        plain
      >
        {{ apiKeyConfigured ? 'Key已设' : '设置Key' }}
      </el-button>
    </div>

    <div v-if="selectedProvider === 'custom'" class="custom-row">
      <el-input
        v-model="customEndpoint"
        placeholder="API地址，如 https://api.xxx.com/v1/chat/completions"
        size="small"
        clearable
      />
    </div>

    <!-- API Key Dialog -->
    <el-dialog
      v-model="showKeyDialog"
      title="设置 API Key"
      width="420px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom:12px"
        :title="`厂商: ${currentProviderName}`"
      />
      <el-input
        v-model="apiKeyInput"
        placeholder="请输入 API Key"
        type="password"
        show-password
        clearable
        @keyup.enter="saveKey"
      />
      <div class="key-hint" v-if="apiKeyConfigured">
        当前已设置 Key: {{ maskedKey }}
      </div>
      <template #footer>
        <el-button @click="showKeyDialog = false">取消</el-button>
        <el-button type="primary" @click="saveKey" :loading="saving">保存</el-button>
        <el-button v-if="apiKeyConfigured" type="danger" plain @click="clearKey">清除Key</el-button>
      </template>
    </el-dialog>

    <!-- RAG Memory Toggle -->
    <div class="rag-row" v-if="selectedProvider">
      <el-divider style="margin:8px 0" />
      <div style="display:flex;align-items:center;justify-content:space-between">
        <span style="font-size:12px;color:#909399">
          RAG 长期记忆
          <el-tooltip content="启用后Bot会检索历史对话中相关的内容作为记忆参考，提升上下文理解能力。向量检索优先，关键词匹配兜底。">
            <el-icon style="vertical-align:middle"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
        <el-switch v-model="ragEnabled" size="small" @change="onRagToggle" :loading="ragLoading" />
      </div>
      <div v-if="ragEnabled" style="margin-top:4px;display:flex;align-items:center;gap:6px">
        <span style="font-size:11px;color:#909399">检索条数:</span>
        <el-select v-model="ragTopK" size="small" style="width:70px" @change="onRagTopKChange">
          <el-option :value="2" label="2" />
          <el-option :value="3" label="3" />
          <el-option :value="5" label="5" />
          <el-option :value="8" label="8" />
        </el-select>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled, Camera } from '@element-plus/icons-vue'
import { listProviders, getProviderConfig, updateProviderConfig, updateRagConfig, uploadBotAvatar } from '../api/bot'

const props = defineProps({
  botUserId: { type: Number, required: true }
})

const emit = defineEmits(['updated'])

const providers = ref([])
const selectedProvider = ref('')
const selectedModel = ref('')
const customEndpoint = ref('')
const apiKeyInput = ref('')
const showKeyDialog = ref(false)
const saving = ref(false)

const apiKeyConfigured = ref(false)
const maskedKey = ref('')
const currentEndpoint = ref('')
const botAvatar = ref('')

async function onAvatarChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const res = await uploadBotAvatar(props.botUserId, file)
    botAvatar.value = res.avatar || (res.data?.avatar)
    ElMessage.success('头像已更新')
  } catch { ElMessage.error('上传失败') }
  finally { e.target.value = '' }
}

// RAG state
const ragEnabled = ref(false)
const ragTopK = ref(3)
const ragLoading = ref(false)

const availableModels = computed(() => {
  const p = providers.value.find(p => p.id === selectedProvider.value)
  return p?.models || []
})

const currentProviderName = computed(() => {
  const p = providers.value.find(p => p.id === selectedProvider.value)
  return p?.name || '自定义'
})

async function loadProviders() {
  try {
    const data = await listProviders()
    providers.value = Array.isArray(data) ? data : []
  } catch (e) { /* ignore */ }
}

async function loadConfig() {
  try {
    const d = await getProviderConfig(props.botUserId)
    if (d) {
      selectedProvider.value = d.providerId || 'custom'
      selectedModel.value = d.model || ''
      apiKeyConfigured.value = d.apiKeyConfigured
      maskedKey.value = d.apiKeyPreview || ''
      currentEndpoint.value = d.apiEndpoint || ''
      ragEnabled.value = d.ragEnabled === true
      ragTopK.value = d.ragTopK || 3
      botAvatar.value = d.avatar || ''
      if (d.providerId === 'custom' && d.apiEndpoint) {
        customEndpoint.value = d.apiEndpoint
      }
    }
  } catch (e) { /* ignore */ }
}

function onProviderChange(val) {
  const p = providers.value.find(p => p.id === val)
  if (p && p.defaultModel) {
    selectedModel.value = p.defaultModel
  }
  if (val !== 'custom') {
    customEndpoint.value = ''
  }
  saveConfig()
}

function onModelChange() {
  saveConfig()
}

async function saveConfig() {
  if (!selectedProvider.value) return
  const data = { providerId: selectedProvider.value, model: selectedModel.value }
  if (selectedProvider.value === 'custom' && customEndpoint.value) {
    data.apiEndpoint = customEndpoint.value
  }
  try {
    await updateProviderConfig(props.botUserId, data)
    emit('updated')
  } catch (e) {
    // ignore silent save
  }
}

async function saveKey() {
  if (!apiKeyInput.value.trim()) {
    ElMessage.warning('请输入 API Key')
    return
  }
  saving.value = true
  try {
    const data = {
      providerId: selectedProvider.value,
      apiKey: apiKeyInput.value.trim(),
      model: selectedModel.value
    }
    if (selectedProvider.value === 'custom' && customEndpoint.value) {
      data.apiEndpoint = customEndpoint.value
    }
    await updateProviderConfig(props.botUserId, data)
    ElMessage.success('API Key 已保存')
    apiKeyConfigured.value = true
    apiKeyInput.value = ''
    showKeyDialog.value = false
    await loadConfig()
    emit('updated')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || '网络错误'))
  } finally {
    saving.value = false
  }
}

async function clearKey() {
  try {
    await updateProviderConfig(props.botUserId, { apiKey: '' })
    apiKeyConfigured.value = false
    maskedKey.value = ''
    apiKeyInput.value = ''
    showKeyDialog.value = false
    ElMessage.success('API Key 已清除')
    emit('updated')
  } catch (e) {
    ElMessage.error('清除失败')
  }
}

async function onRagToggle(val) {
  ragLoading.value = true
  try {
    await updateRagConfig(props.botUserId, val, ragTopK.value)
    ElMessage.success(val ? 'RAG 长期记忆已启用' : 'RAG 长期记忆已关闭')
    emit('updated')
  } catch {
    ragEnabled.value = !val
    ElMessage.error('更新失败')
  } finally {
    ragLoading.value = false
  }
}

async function onRagTopKChange(val) {
  try {
    await updateRagConfig(props.botUserId, ragEnabled.value, val)
  } catch { /* ignore */ }
}

onMounted(async () => {
  await loadProviders()
  await loadConfig()
})
</script>

<style scoped>
.provider-selector {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.provider-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.custom-row {
  margin-top: 2px;
}
.key-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-success);
}
</style>
