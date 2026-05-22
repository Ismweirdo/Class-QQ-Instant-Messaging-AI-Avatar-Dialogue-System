<template>
  <el-dialog v-model="visible" title="导入人物" width="520px" @close="onClose">
    <el-tabs v-model="activeTab">
      <!-- Tab 1: URL Import -->
      <el-tab-pane label="URL 导入" name="url">
        <el-alert type="info" :closable="false" style="margin-bottom:16px">
          <template #title>输入 GitHub 仓库地址</template>
          支持格式：<code>https://github.com/用户名/仓库名.git</code>。系统将自动获取 SKILL.md 生成机器人。
        </el-alert>

        <el-input
          v-model="url"
          placeholder="https://github.com/a18515373115-droid/ZhangXueFeng-skill.git"
          clearable
          @keyup.enter="doImportFromUrl"
        >
          <template #append>
            <el-button @click="doImportFromUrl" :loading="importingUrl">
              获取并导入
            </el-button>
          </template>
        </el-input>

        <div v-if="importedSkill" class="result-card">
          <div class="result-header">
            <el-avatar :size="40" src="https://api.dicebear.com/7.x/bottts/svg?seed=imported" />
            <div class="result-info">
              <div class="result-name">{{ importedSkill.skillName }}</div>
              <div class="result-hint">导入成功，已添加为好友</div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- Tab 2: File Upload -->
      <el-tab-pane label="文件上传" name="file">
        <el-alert type="info" :closable="false" style="margin-bottom:16px">
          <template #title>上传 Skill 文件</template>
          上传 .md 格式的 Skill 文件，自动解析并创建机器人。
        </el-alert>

        <el-upload
          ref="uploadRef"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".md"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
        >
          <el-icon :size="48" color="#409EFF"><UploadFilled /></el-icon>
          <div class="upload-text">
            <p>将 .md 文件拖到此处，或点击上传</p>
            <p class="hint">仅支持 .md 格式的 Skill 文件</p>
          </div>
        </el-upload>

        <div v-if="fileReady" style="margin-top:16px;text-align:center">
          <el-button type="primary" @click="doImportFromFile" :loading="importingFile">
            导入 {{ fileName }}
          </el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { importSkillFromUrl, importSkillFile } from '../api/bot'

const emit = defineEmits(['update:modelValue', 'imported'])
const props = defineProps({ modelValue: Boolean })

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const activeTab = ref('url')

// URL tab
const url = ref('')
const importingUrl = ref(false)
const importedSkill = ref(null)

// File tab
const uploadRef = ref(null)
const fileReady = ref(false)
const fileName = ref('')
let selectedFile = null
const importingFile = ref(false)

async function doImportFromUrl() {
  if (!url.value.trim()) {
    ElMessage.warning('请输入 URL')
    return
  }
  importingUrl.value = true
  importedSkill.value = null
  try {
    const res = await importSkillFromUrl(url.value.trim())
    if (res.code === 200) {
      importedSkill.value = res.data
      ElMessage.success(`人物 "${res.data.skillName}" 导入成功`)
      emit('imported')
    } else {
      ElMessage.error(res.message || '导入失败')
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.message || '网络错误'))
  } finally {
    importingUrl.value = false
  }
}

function onFileChange(file) {
  selectedFile = file.raw
  fileName.value = file.name
  fileReady.value = true
}

function onFileRemove() {
  selectedFile = null
  fileName.value = ''
  fileReady.value = false
}

async function doImportFromFile() {
  if (!selectedFile) return
  importingFile.value = true
  try {
    const res = await importSkillFile(selectedFile)
    if (res.code === 200) {
      ElMessage.success(`人物 "${res.data.skillName}" 导入成功`)
      emit('imported')
      visible.value = false
    } else {
      ElMessage.error(res.message || '导入失败')
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.message || e))
  } finally {
    importingFile.value = false
  }
}

function onClose() {
  url.value = ''
  importedSkill.value = null
  fileReady.value = false
  fileName.value = ''
  selectedFile = null
  activeTab.value = 'url'
}

watch(visible, (val) => { if (!val) onClose() })
</script>

<style scoped>
.result-card {
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--bg-secondary, #f0f9eb);
  border-radius: 8px;
  border: 1px solid var(--color-success-light, #e1f3d8);
}
.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.result-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #303133);
}
.result-hint {
  font-size: 12px;
  color: var(--color-success, #67c23a);
  margin-top: 2px;
}

.upload-text p { margin: 4px 0; }
.upload-text .hint { font-size: 12px; color: #999; }
</style>
