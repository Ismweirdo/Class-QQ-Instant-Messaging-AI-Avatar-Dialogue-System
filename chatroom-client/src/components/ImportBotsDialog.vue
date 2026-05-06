<template>
  <el-dialog v-model="visible" title="导入聊天记录生成机器人" width="600px" @open="onOpen">
    <!-- Step 1: Upload -->
    <div v-if="step === 1">
      <el-alert type="info" :closable="false" style="margin-bottom:16px">
        <template #title>
          支持格式：JSONL、JSON、TXT（微信/QQ导出格式）
        </template>
        每条消息需包含发送者名称和消息内容。系统将自动分析每个人的语言风格和情绪模式，生成对应机器人。
      </el-alert>

      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".json,.jsonl,.txt"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-icon :size="48" color="#409EFF"><UploadFilled /></el-icon>
        <div class="upload-text">
          <p>将聊天记录文件拖到此处，或点击上传</p>
          <p style="font-size:12px;color:#999;">JSON / JSONL / TXT</p>
        </div>
      </el-upload>

      <div v-if="fileReady" style="margin-top:16px;text-align:center;">
        <el-button type="primary" size="large" @click="doImport" :loading="importing">
          开始分析并生成机器人
        </el-button>
      </div>
    </div>

    <!-- Step 2: Results -->
    <div v-else>
      <el-alert :type="results.length > 0 ? 'success' : 'warning'" :closable="false" style="margin-bottom:16px">
        <template #title>
          {{ results.length > 0 ? `成功生成 ${results.length} 个机器人` : '未生成机器人' }}
        </template>
        {{ results.length > 0 ? '以下机器人已自动注册并上线，加为好友后即可聊天。' : '文件中没有足够的数据来生成机器人（每人至少10条消息）。' }}
      </el-alert>

      <div v-if="results.length > 0" class="result-list">
        <div v-for="(bot, idx) in results" :key="idx" class="result-item">
          <el-avatar :size="36">{{ bot.nickname[0] }}</el-avatar>
          <div class="result-info">
            <div class="result-name">{{ bot.nickname }}</div>
            <div class="result-meta">
              {{ bot.messageCount }}条消息 |
              主导情绪: {{ emotionLabel(bot.dominantEmotion) }} |
              平均句长: {{ bot.languageStyle?.avgSentenceLen || '-' }}字
            </div>
          </div>
          <el-tag :type="tagColor(bot.dominantEmotion)" size="small">
            {{ emotionLabel(bot.dominantEmotion) }}
          </el-tag>
        </div>
      </div>

      <div style="text-align:center;margin-top:16px;">
        <el-button @click="reset">继续导入</el-button>
        <el-button type="primary" @click="close">完成</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { importChatRecords } from '../api/bot'
import { ElMessage } from 'element-plus'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible', 'done'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const step = ref(1)
const fileReady = ref(false)
const importing = ref(false)
const results = ref([])
const uploadRef = ref(null)

function onOpen() {
  step.value = 1
  fileReady.value = false
  importing.value = false
  results.value = []
}

function onFileChange(file) {
  fileReady.value = true
}

function onFileRemove() {
  fileReady.value = false
}

async function doImport() {
  if (!uploadRef.value?.uploadFiles?.[0]?.raw) {
    ElMessage.warning('请先选择文件')
    return
  }
  importing.value = true
  try {
    const file = uploadRef.value.uploadFiles[0].raw
    results.value = await importChatRecords(file)
    step.value = 2
    if (results.value.length > 0) {
      ElMessage.success(`成功生成 ${results.value.length} 个机器人`)
      emit('done')
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.message || '未知错误'))
  } finally {
    importing.value = false
  }
}

function reset() {
  step.value = 1
  fileReady.value = false
  results.value = []
  if (uploadRef.value) uploadRef.value.clearFiles()
}

function close() {
  visible.value = false
  emit('done')
}

function emotionLabel(key) {
  const map = { joy: '😊 乐观', anger: '😤 直率', sad: '😢 细腻', surprise: '😲 夸张', fear: '😰 谨慎', care: '💕 贴心' }
  return map[key] || key
}

function tagColor(key) {
  const map = { joy: 'success', anger: 'danger', sad: 'info', surprise: 'warning', fear: '', care: 'success' }
  return map[key] || ''
}
</script>

<style scoped>
.upload-text { text-align:center; margin-top:12px; }
.upload-text p { margin:4px 0; }

.result-list { max-height:360px; overflow-y:auto; }
.result-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.result-item:last-child { border-bottom: none; }
.result-info { flex:1; }
.result-name { font-size:14px; font-weight:600; }
.result-meta { font-size:12px; color:#999; margin-top:2px; }
</style>
