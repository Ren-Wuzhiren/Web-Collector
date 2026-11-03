<script setup>
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

onMounted(async () => {
  try {
    await auth.loadCurrentUser()
  } catch {
    // [API] 401 会由 axios 拦截器清理 token 并跳转登录页。
  }
})

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-header v-if="route.path !== '/login'" class="topbar">
      <div class="brand" @click="router.push('/links')">WebCollector</div>
      <el-menu mode="horizontal" :default-active="route.path" router>
        <el-menu-item index="/links">收藏</el-menu-item>
        <el-menu-item index="/search">搜索</el-menu-item>
      </el-menu>
      <div class="account">
        <span>{{ auth.user?.username }}</span>
        <el-button link @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
:root {
  font-family: Inter, "Microsoft YaHei", system-ui, sans-serif;
  color: #1f2937;
  background: #f4f6f8;
}
body {
  margin: 0;
}
.app-shell {
  min-height: 100vh;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 28px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  padding: 0 32px;
}
.brand {
  font-weight: 750;
  font-size: 20px;
  white-space: nowrap;
  cursor: pointer;
}
.topbar .el-menu {
  flex: 1;
  border-bottom: 0;
}
.account {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-card {
  max-width: 1180px;
  margin: 0 auto;
}
.muted {
  color: #6b7280;
}
</style>
