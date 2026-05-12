<template>
  <div class="register-container">
    <div class="register-bg"></div>
    <div class="register-bg-2"></div>
    
    <div class="register-card">
      <div class="card-header">
        <div class="logo-wrapper">
          <div class="logo">
            <el-icon :size="48" color="white"><Plus /></el-icon>
          </div>
        </div>
        <h1 class="title">创建账号</h1>
        <p class="subtitle">加入我们，开始新的社交之旅</p>
      </div>
      
      <el-form :model="form" :rules="rules" ref="formRef" label-width="0">
        <el-form-item prop="nickname">
          <div class="input-group">
            <el-icon class="input-icon"><UserFilled /></el-icon>
            <el-input v-model="form.nickname" placeholder="昵称" size="large" />
          </div>
        </el-form-item>
        <el-form-item prop="password">
          <div class="input-group">
            <el-icon class="input-icon"><Lock /></el-icon>
            <el-input v-model="form.password" type="password" placeholder="密码（至少6位）" size="large"
              show-password />
          </div>
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <div class="input-group">
            <el-icon class="input-icon"><Lock /></el-icon>
            <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large"
              show-password />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="register-btn" @click="handleRegister" :loading="loading">
            <span v-if="!loading">注 册</span>
            <span v-else>注册中...</span>
          </el-button>
        </el-form-item>
      </el-form>
      
      <div class="footer">
        <span>已有账号？</span>
        <router-link to="/login" class="login-link">返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'
import { User, UserFilled, Lock, Plus } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  nickname: '',
  password: '',
  confirmPassword: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次密码输入不一致'))
  } else {
    callback()
  }
}

const rules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 1, max: 50, message: '昵称长度为1-50个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

async function handleRegister() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await userStore.register({
      password: form.password,
      nickname: form.nickname
    })
    ElMessage.success('注册成功！您的账号是：' + result.user.username + '，请牢记')
    router.push('/chat')
  } catch (e) {
    // Error handled by interceptor
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%);
  position: relative;
  overflow: hidden;
}

.register-bg,
.register-bg-2 {
  position: absolute;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  opacity: 0.3;
  animation: float 20s ease-in-out infinite;
}

.register-bg {
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  top: -200px;
  right: -200px;
}

.register-bg-2 {
  background: linear-gradient(135deg, #6366f1, #3b82f6);
  bottom: -200px;
  left: -200px;
  animation-delay: -10s;
}

@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(50px, 50px) scale(1.1);
  }
}

.register-card {
  width: 420px;
  padding: 48px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 24px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25),
              0 0 0 1px rgba(255, 255, 255, 0.1);
  position: relative;
  z-index: 10;
  animation: card-appear 0.5s ease-out;
}

@keyframes card-appear {
  from {
    opacity: 0;
    transform: translateY(20px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.card-header {
  text-align: center;
  margin-bottom: 36px;
}

.logo-wrapper {
  margin-bottom: 16px;
}

.logo {
  width: 80px;
  height: 80px;
  margin: 0 auto;
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  border-radius: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 40px rgba(139, 92, 246, 0.4);
  animation: logo-pulse 2s ease-in-out infinite;
}

@keyframes logo-pulse {
  0%, 100% {
    transform: scale(1);
    box-shadow: 0 10px 40px rgba(139, 92, 246, 0.4);
  }
  50% {
    transform: scale(1.05);
    box-shadow: 0 15px 50px rgba(139, 92, 246, 0.5);
  }
}

.title {
  font-size: 32px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 8px;
  letter-spacing: -0.5px;
}

.subtitle {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

.input-group {
  position: relative;
  margin-bottom: 8px;
}

.input-icon {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  color: #94a3b8;
  font-size: 18px;
  z-index: 1;
}

.input-group :deep(.el-input__wrapper) {
  padding-left: 48px;
  border-radius: 16px;
  height: 52px;
  transition: all 0.3s ease;
}

.input-group :deep(.el-input__inner) {
  font-size: 15px;
}

.register-btn {
  width: 100%;
  height: 52px;
  border-radius: 16px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 2px;
  margin-top: 8px;
}

.footer {
  text-align: center;
  color: #94a3b8;
  margin-top: 24px;
  font-size: 14px;
}

.login-link {
  color: #6366f1;
  font-weight: 600;
  text-decoration: none;
  margin-left: 4px;
  transition: all 0.3s ease;
}

.login-link:hover {
  color: #4f46e5;
  text-decoration: underline;
}
</style>
