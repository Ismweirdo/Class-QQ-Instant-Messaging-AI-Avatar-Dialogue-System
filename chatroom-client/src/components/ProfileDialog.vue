<template>
  <el-dialog v-model="visible" title="个人资料" width="420px" @close="onClose">
    <div class="profile-content">
      <div class="avatar-section" @click="triggerUpload">
        <img v-if="avatarPreview && !avatarPreview.includes('default')" :src="avatarPreview" class="avatar-img" />
        <span v-else class="avatar-placeholder">{{ displayName?.[0] || '?' }}</span>
        <div class="avatar-overlay">更换头像</div>
      </div>
      <input ref="fileInput" type="file" accept="image/*" hidden @change="onFileChange" />

      <el-form :model="form" label-position="top" style="margin-top:20px">
        <el-form-item label="账号">
          <el-input :model-value="userStore.username" disabled />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="请输入昵称" maxlength="50" />
        </el-form-item>
      </el-form>

      <div v-if="newAvatarFile" class="preview-notice">
        <img v-if="avatarPreview" :src="avatarPreview" />
        <span>新头像预览，保存后生效</span>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save" :loading="saving">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useUserStore } from '../store/user'
import { updateProfile, uploadAvatar } from '../api/user'
import { ElMessage } from 'element-plus'

const props = defineProps({ visible: Boolean, displayName: String })
const emit = defineEmits(['update:visible', 'updated'])

const userStore = useUserStore()

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const fileInput = ref(null)
const saving = ref(false)
const form = ref({ nickname: '' })
const newAvatarFile = ref(null)
const avatarPreview = ref('')

watch(() => props.visible, (val) => {
  if (val) {
    form.value.nickname = userStore.nickname || ''
    newAvatarFile.value = null
    avatarPreview.value = userStore.user?.avatar || ''
  }
})

function triggerUpload() {
  fileInput.value?.click()
}

function onFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.error('仅支持 jpg、png、gif、webp 格式')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('头像文件不能超过5MB')
    return
  }
  newAvatarFile.value = file
  const reader = new FileReader()
  reader.onload = (ev) => { avatarPreview.value = ev.target.result }
  reader.readAsDataURL(file)
  e.target.value = ''
}

function onClose() {
  newAvatarFile.value = null
  avatarPreview.value = ''
}

async function save() {
  saving.value = true
  try {
    if (newAvatarFile.value) {
      const avatarUrl = await uploadAvatar(newAvatarFile.value)
      userStore.user.avatar = avatarUrl
      localStorage.setItem('user', JSON.stringify(userStore.user))
    }
    if (form.value.nickname && form.value.nickname !== userStore.nickname) {
      const updated = await updateProfile({ nickname: form.value.nickname })
      userStore.user.nickname = updated.nickname
      localStorage.setItem('user', JSON.stringify(userStore.user))
    }
    ElMessage.success('资料已更新')
    visible.value = false
    emit('updated')
  } catch (e) {
    // Error handled by interceptor
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-content {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.avatar-section {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  position: relative;
  cursor: pointer;
  overflow: hidden;
  border: 3px solid var(--primary-color, #6366f1);
}

.avatar-section:hover .avatar-overlay {
  opacity: 1;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 36px;
  font-weight: 600;
  color: white;
  background: linear-gradient(135deg, var(--primary-color, #6366f1), var(--secondary-color, #8b5cf6));
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 13px;
  font-weight: 500;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.preview-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 10px 14px;
  background: var(--bg-secondary, #f1f5f9);
  border-radius: 10px;
  font-size: 13px;
  color: var(--text-muted, #64748b);
}

.preview-notice img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}
</style>
