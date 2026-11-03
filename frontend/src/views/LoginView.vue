<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const mode = ref('login')
const loading = ref(false)
const form = reactive({
  username: '',
  email: '',
  password: '',
})

async function submit() {
  loading.value = true
  try {
    if (mode.value === 'register') {
      await auth.register(form.username, form.email, form.password)
      ElMessage.success('注册成功，请登录')
      mode.value = 'login'
      return
    }
    await auth.login(form.username, form.password)
    await router.push('/links')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>WebCollector</h1>
      <p class="muted">把网页保存成可搜索、可回看的个人资料库</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="邮箱">
          <el-input v-model="form.email" autocomplete="email" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" block>
          {{ mode === 'login' ? '登录' : '注册' }}
        </el-button>
      </el-form>
      <el-button link class="switch" @click="mode = mode === 'login' ? 'register' : 'login'">
        {{ mode === 'login' ? '没有账号？注册' : '返回登录' }}
      </el-button>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #eef4ff, #f8fafc 60%, #ecfdf5);
}
.login-card {
  width: min(420px, calc(100vw - 40px));
  padding: 12px;
}
h1 {
  margin: 8px 0;
}
.switch {
  margin-top: 18px;
}
</style>
