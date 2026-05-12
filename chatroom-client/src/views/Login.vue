<template>
  <div class="login-container">
    <div class="login-bg"></div>
    <div class="login-bg-2"></div>
    
    <div class="login-card">
      <div class="card-header">
        <div class="logo-wrapper">
          <div class="logo">
            <el-icon :size="48" color="white"><Message /></el-icon>
          </div>
        </div>
        <h1 class="title">Chatroom</h1>
        <p class="subtitle">开启您的聊天之旅</p>
      </div>
      
      <el-form :model="form" :rules="rules" ref="formRef" label-width="0">
        <el-form-item prop="username">
          <div class="input-group">
            <el-icon class="input-icon"><User /></el-icon>
            <el-input v-model="form.username" placeholder="账号" size="large" />
          </div>
        </el-form-item>
        <el-form-item prop="password">
          <div class="input-group">
            <el-icon class="input-icon"><Lock /></el-icon>
            <el-input v-model="form.password" type="password" placeholder="密码" size="large"
              @keyup.enter="handleLogin" show-password />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="login-btn" @click="handleLogin" :loading="loading">
            <span v-if="!loading">登 录</span>
            <span v-else>登录中...</span>
          </el-button>
        </el-form-item>
      </el-form>
      
      <div class="footer">
        <span>还没有账号？</span>
        <router-link to="/register" class="register-link">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'
import { Message, User, Lock } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    router.push('/chat')
  } catch (e) {
    // Error handled by interceptor
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #1e1b4b 0%, #312e81 50%, #1e1b4b 100%);
  position: relative;
  overflow: hidden;
}

.login-bg,
.login-bg-2 {
  position: absolute;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  opacity: 0.3;
  animation: float 20s ease-in-out infinite;
}

.login-bg {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  top: -200px;
  left: -200px;
}

.login-bg-2 {
  background: linear-gradient(135deg, #ec4899, #f472b6);
  bottom: -200px;
  right: -200px;
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

.login-card {
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
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  border-radius: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 40px rgba(99, 102, 241, 0.4);
  animation: logo-pulse 2s ease-in-out infinite;
}

@keyframes logo-pulse {
  0%, 100% {
    transform: scale(1);
    box-shadow: 0 10px 40px rgba(99, 102, 241, 0.4);
  }
  50% {
    transform: scale(1.05);
    box-shadow: 0 15px 50px rgba(99, 102, 241, 0.5);
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

.login-btn {
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

.register-link {
  color: #6366f1;
  font-weight: 600;
  text-decoration: none;
  margin-left: 4px;
  transition: all 0.3s ease;
}

.register-link:hover {
  color: #4f46e5;
  text-decoration: underline;
}
</style>
