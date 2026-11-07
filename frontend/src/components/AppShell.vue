<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ArchiveMark from './ArchiveMark.vue'
import AppIcon from './AppIcon.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)

const navItems = computed(() => [
  { to: '/links', label: '收藏', icon: 'links' },
  { to: '/search', label: '搜索', icon: 'search' },
  { to: '/tags', label: '标签', icon: 'tag' },
  ...(auth.isAdmin
    ? [{ to: '/admin', label: '管理', icon: 'archive' }]
    : []),
])

const currentTitle = computed(() => {
  if (route.path.startsWith('/links')) return '收藏'
  if (route.path.startsWith('/search')) return '搜索'
  if (route.path.startsWith('/tags')) return '标签'
  if (route.path.startsWith('/admin')) return '管理'
  return 'WebCollector'
})

watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false
  },
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  auth.loadCurrentUser().catch(() => {
    // [API] 401 会由统一拦截器清理 token 并跳转到登录页。
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

function onKeydown(event) {
  if (event.key === 'Escape' && mobileOpen.value) {
    mobileOpen.value = false
  }
}

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="app-layout">
    <a class="skip-link" href="#main-content">跳到主要内容</a>

    <aside class="sidebar" aria-label="主导航">
      <router-link class="brand" to="/links" aria-label="WebCollector 收藏">
        <span class="brand-mark">
          <ArchiveMark :size="30" />
        </span>
        <span class="brand-copy">
          <strong>WebCollector</strong>
          <small>个人档案</small>
        </span>
      </router-link>

      <nav class="nav-group" aria-label="档案页面">
        <p class="nav-label">我的档案</p>
        <router-link
          v-for="item in navItems"
          :key="item.to"
          class="nav-item"
          :to="item.to"
          active-class="is-active"
        >
          <AppIcon :name="item.icon" :size="17" />
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="account-row">
        <span class="account-avatar" aria-hidden="true">
          {{ auth.user?.username?.slice(0, 1)?.toUpperCase() || 'U' }}
        </span>
        <span class="account-copy">
          <strong>{{ auth.user?.username || '当前账号' }}</strong>
          <small>自托管档案</small>
        </span>
        <button class="icon-button" type="button" aria-label="退出登录" @click="logout">
          <AppIcon name="logout" :size="17" />
        </button>
      </div>
    </aside>

    <header class="mobile-bar">
      <button
        class="mobile-menu"
        type="button"
        aria-label="打开导航"
        :aria-expanded="mobileOpen"
        @click="mobileOpen = true"
      >
        <AppIcon name="menu" :size="20" />
      </button>
      <span class="mobile-title">{{ currentTitle }}</span>
      <span class="mobile-brand-mark" aria-hidden="true">
        <ArchiveMark :size="22" />
      </span>
    </header>

    <main id="main-content" class="app-main" tabindex="-1">
      <slot />
    </main>

    <transition name="sheet">
      <div
        v-if="mobileOpen"
        class="mobile-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="导航"
        @click.self="mobileOpen = false"
      >
        <div class="mobile-sheet-panel">
          <header class="mobile-sheet-head">
            <router-link class="brand" to="/links">
              <span class="brand-mark">
                <ArchiveMark :size="30" />
              </span>
              <span class="brand-copy">
                <strong>WebCollector</strong>
                <small>个人档案</small>
              </span>
            </router-link>
            <button
              class="icon-button"
              type="button"
              aria-label="关闭导航"
              @click="mobileOpen = false"
            >
              <AppIcon name="close" :size="20" />
            </button>
          </header>
          <nav class="mobile-nav" aria-label="移动端主导航">
            <router-link
              v-for="item in navItems"
              :key="item.to"
              class="mobile-nav-item"
              :to="item.to"
              active-class="is-active"
            >
              <AppIcon :name="item.icon" :size="19" />
              <span>{{ item.label }}</span>
              <AppIcon class="mobile-chevron" name="chevron" :size="16" />
            </router-link>
          </nav>
          <div class="mobile-account">
            <span class="account-avatar" aria-hidden="true">
              {{ auth.user?.username?.slice(0, 1)?.toUpperCase() || 'U' }}
            </span>
            <span class="account-copy">
              <strong>{{ auth.user?.username || '当前账号' }}</strong>
              <small>自托管档案</small>
            </span>
            <button class="text-button" type="button" @click="logout">退出</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.app-layout {
  min-height: 100svh;
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  background: var(--ground);
}

.skip-link {
  position: fixed;
  top: 8px;
  left: 8px;
  z-index: 100;
  padding: 10px 14px;
  border-radius: var(--radius-control);
  color: var(--surface);
  background: var(--text);
  text-decoration: none;
  transform: translateY(-160%);
  transition: transform var(--motion-fast) var(--ease-out);
}

.skip-link:focus {
  transform: translateY(0);
}

.sidebar {
  position: sticky;
  top: 0;
  height: 100svh;
  padding: 24px 16px 18px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
}

.brand {
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 8px 38px;
  color: var(--text);
  text-decoration: none;
}

.brand-mark,
.mobile-brand-mark {
  display: grid;
  place-items: center;
  color: var(--text);
}

.brand-mark {
  width: 32px;
  height: 34px;
}

.brand-copy {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.brand-copy strong {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 500;
  letter-spacing: -0.01em;
}

.brand-copy small,
.account-copy small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
}

.nav-group {
  display: grid;
  gap: 4px;
}

.nav-label {
  margin: 0 10px 10px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
}

.nav-item {
  min-height: 38px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-radius: var(--radius-control);
  color: var(--muted);
  text-decoration: none;
  transition:
    color var(--motion-fast) var(--ease-out),
    background-color var(--motion-fast) var(--ease-out);
}

.nav-item:hover {
  color: var(--text);
  background: rgb(255 255 255 / 58%);
}

.nav-item.is-active {
  color: var(--text);
  background: var(--active);
}

.account-row {
  min-width: 0;
  min-height: 58px;
  margin-top: auto;
  padding: 10px 8px;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 32px;
  align-items: center;
  gap: 9px;
  border-top: 1px solid var(--border);
}

.account-avatar {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text);
  background: var(--active);
  font-size: 12px;
  font-weight: 600;
}

.account-copy {
  min-width: 0;
  display: grid;
}

.account-copy strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icon-button,
.mobile-menu,
.text-button {
  border: 0;
  color: var(--muted);
  background: transparent;
}

.icon-button {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-control);
}

.icon-button:hover {
  color: var(--text);
  background: var(--active);
}

.app-main {
  min-width: 0;
}

.mobile-bar,
.mobile-sheet {
  display: none;
}

@media (max-width: 900px) {
  .app-layout {
    display: block;
  }

  .sidebar {
    display: none;
  }

  .mobile-bar {
    position: sticky;
    top: 0;
    z-index: 20;
    height: 48px;
    padding: 0 8px;
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr) 44px;
    align-items: center;
    border-bottom: 1px solid var(--border);
    background: var(--ground);
  }

  .mobile-menu {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
  }

  .mobile-title {
    text-align: center;
    font-size: 14px;
    font-weight: 550;
  }

  .mobile-brand-mark {
    width: 44px;
    height: 44px;
  }

  .mobile-sheet {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: block;
    background: rgb(10 10 10 / 24%);
  }

  .mobile-sheet-panel {
    width: min(100%, 420px);
    min-height: 100%;
    padding: 12px 16px 24px;
    display: flex;
    flex-direction: column;
    background: var(--ground);
  }

  .mobile-sheet-head {
    min-height: 48px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .mobile-sheet-head .brand {
    margin: 0;
  }

  .mobile-nav {
    margin-top: 30px;
    display: grid;
    gap: 4px;
  }

  .mobile-nav-item {
    min-height: 52px;
    padding: 0 12px;
    display: grid;
    grid-template-columns: 24px minmax(0, 1fr) 20px;
    align-items: center;
    gap: 10px;
    border-radius: var(--radius-control);
    color: var(--muted);
    text-decoration: none;
  }

  .mobile-nav-item.is-active {
    color: var(--text);
    background: var(--active);
  }

  .mobile-chevron {
    justify-self: end;
  }

  .mobile-account {
    margin-top: auto;
    padding: 16px 10px 0;
    display: grid;
    grid-template-columns: 32px minmax(0, 1fr) auto;
    align-items: center;
    gap: 10px;
    border-top: 1px solid var(--border);
  }

  .text-button {
    min-width: 44px;
    min-height: 44px;
  }

  .sheet-enter-active,
  .sheet-leave-active {
    transition: opacity var(--motion-standard) var(--ease-out);
  }

  .sheet-enter-active .mobile-sheet-panel,
  .sheet-leave-active .mobile-sheet-panel {
    transition: transform var(--motion-standard) var(--ease-out);
  }

  .sheet-enter-from,
  .sheet-leave-to {
    opacity: 0;
  }

  .sheet-enter-from .mobile-sheet-panel,
  .sheet-leave-to .mobile-sheet-panel {
    transform: translateX(-16px);
  }
}
</style>
