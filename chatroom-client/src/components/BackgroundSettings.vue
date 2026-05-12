<template>
  <el-dialog v-model="visible" title="聊天背景" width="520px">
    <div class="bg-content">
      <div class="bg-section">
        <h4 class="section-title">预设纯色</h4>
        <div class="color-grid">
          <div v-for="c in solidColors" :key="c" class="color-swatch"
            :style="{ background: c }" :class="{ active: current?.type === 'color' && current.value === c }"
            @click="selectColor(c)"></div>
          <div class="color-swatch reset-swatch" @click="resetBg" title="恢复默认">
            <el-icon><Refresh /></el-icon>
          </div>
        </div>
      </div>

      <div class="bg-section">
        <h4 class="section-title">预设渐变</h4>
        <div class="gradient-list">
          <div v-for="g in gradients" :key="g.name" class="gradient-swatch"
            :style="{ background: g.value }"
            :class="{ active: current?.type === 'gradient' && current.value === g.value }"
            @click="selectGradient(g)">
            <span>{{ g.name }}</span>
          </div>
        </div>
      </div>

      <div class="bg-section">
        <h4 class="section-title">自定义图片</h4>
        <div class="image-upload">
          <el-button @click="triggerImageUpload" size="small">
            <el-icon><Upload /></el-icon> 选择图片
          </el-button>
          <input ref="imageInput" type="file" accept="image/*" hidden @change="onImageChange" />
          <span v-if="imageName" class="image-name">{{ imageName }}</span>
        </div>
      </div>

      <div v-if="previewBg" class="bg-preview" :style="previewBg">
        <span class="preview-label">预览效果</span>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="current" text type="danger" @click="resetBg">恢复默认</el-button>
      <el-button type="primary" @click="applyBg" :loading="applying">应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Upload, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const STORAGE_KEY = 'chat-background'

const solidColors = [
  '#f8fafc', '#f1f5f9', '#e2e8f0', '#fef3c7', '#fce7f3',
  '#ede9fe', '#e0f2fe', '#dcfce7', '#fff7ed', '#fdf2f8',
  '#ecfdf5', '#eff6ff', '#f5f3ff', '#fef2f2', '#f0fdf4'
]

const gradients = [
  { name: '紫韵', value: 'linear-gradient(135deg, #e0e7ff 0%, #f3e8ff 100%)' },
  { name: '日落', value: 'linear-gradient(135deg, #fef3c7 0%, #fce7f3 100%)' },
  { name: '海洋', value: 'linear-gradient(135deg, #e0f2fe 0%, #dcfce7 100%)' },
  { name: '玫瑰', value: 'linear-gradient(135deg, #fce7f3 0%, #ede9fe 100%)' },
  { name: '清晨', value: 'linear-gradient(135deg, #dbeafe 0%, #f3e8ff 100%)' },
  { name: '温暖', value: 'linear-gradient(135deg, #fff7ed 0%, #fef3c7 100%)' },
  { name: '暗夜', value: 'linear-gradient(135deg, #1e293b 0%, #334155 100%)' },
  { name: '薄荷', value: 'linear-gradient(135deg, #d1fae5 0%, #a7f3d0 100%)' }
]

const current = ref(null)
const previewBg = ref(null)
const applying = ref(false)
const imageInput = ref(null)
const imageName = ref('')
const pendingImage = ref(null)

watch(() => props.visible, (val) => {
  if (val) {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      current.value = stored ? JSON.parse(stored) : null
    } catch { current.value = null }
    previewBg.value = getBgStyle(current.value)
    imageName.value = ''
    pendingImage.value = null
  }
})

function getBgStyle(bg) {
  if (!bg) return null
  if (bg.type === 'color') return { backgroundColor: bg.value }
  if (bg.type === 'gradient') return { background: bg.value }
  if (bg.type === 'image') return { backgroundImage: `url(${bg.value})`, backgroundSize: 'cover', backgroundPosition: 'center' }
  return null
}

function selectColor(color) {
  current.value = { type: 'color', value: color }
  previewBg.value = getBgStyle(current.value)
}

function selectGradient(g) {
  current.value = { type: 'gradient', value: g.value }
  previewBg.value = getBgStyle(current.value)
}

function triggerImageUpload() {
  imageInput.value?.click()
}

function onImageChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('图片不能超过2MB')
    return
  }
  imageName.value = file.name
  const reader = new FileReader()
  reader.onload = (ev) => {
    pendingImage.value = ev.target.result
    current.value = { type: 'image', value: ev.target.result }
    previewBg.value = getBgStyle(current.value)
  }
  reader.readAsDataURL(file)
  e.target.value = ''
}

function applyBg() {
  if (current.value) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(current.value))
  } else {
    localStorage.removeItem(STORAGE_KEY)
  }
  ElMessage.success('背景已更新')
  visible.value = false
}

function resetBg() {
  current.value = null
  previewBg.value = null
  localStorage.removeItem(STORAGE_KEY)
  ElMessage.success('已恢复默认背景')
  visible.value = false
}
</script>

<style scoped>
.bg-content {
  max-height: 480px;
  overflow-y: auto;
}

.bg-section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 10px;
}

.color-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.color-swatch {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  cursor: pointer;
  border: 3px solid transparent;
  transition: all 0.2s ease;
}

.color-swatch:hover {
  transform: scale(1.1);
}

.color-swatch.active {
  border-color: var(--primary-color, #6366f1);
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.3);
}

.reset-swatch {
  border: 2px dashed #d1d5db;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 18px;
}

.gradient-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.gradient-swatch {
  width: 100px;
  height: 56px;
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: flex-end;
  padding: 6px 8px;
  border: 3px solid transparent;
  transition: all 0.2s ease;
}

.gradient-swatch span {
  font-size: 11px;
  color: #334155;
  font-weight: 500;
  background: rgba(255, 255, 255, 0.6);
  padding: 1px 6px;
  border-radius: 4px;
}

.gradient-swatch:hover {
  transform: scale(1.05);
}

.gradient-swatch.active {
  border-color: var(--primary-color, #6366f1);
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.3);
}

.image-upload {
  display: flex;
  align-items: center;
  gap: 12px;
}

.image-name {
  font-size: 13px;
  color: var(--text-muted);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bg-preview {
  margin-top: 20px;
  height: 120px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-light);
  transition: all 0.3s ease;
}

.preview-label {
  font-size: 14px;
  color: #64748b;
  background: rgba(255, 255, 255, 0.7);
  padding: 4px 12px;
  border-radius: 6px;
}
</style>
