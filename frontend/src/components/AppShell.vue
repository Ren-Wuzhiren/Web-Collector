<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ArchiveMark from './ArchiveMark.vue'
import AppIcon from './AppIcon.vue'
import ThemeToggle from './ThemeToggle.vue'
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

    <header class="site-header">
      <div class="header-inner">
        <router-link class="brand" to="/links" aria-label="WebCollector 收藏">
          <span class="brand-mark">
            <ArchiveMark :size="28" />
          </span>
          <span class="brand-copy">
            <strong>WebCollector</strong>
            <small>个人档案</small>
          </span>
        </router-link>

        <nav class="desktop-nav" aria-label="主导航">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            class="nav-item"
            :to="item.to"
            active-class="is-active"
          >
            <AppIcon :name="item.icon" :size="16" />
            <span>{{ item.label }}</span>
          </router-link>
        </nav>

        <div class="header-actions">
          <ThemeToggle compact />

          <div class="account-cluster">
            <span class="account-avatar" aria-hidden="true">
              {{ auth.user?.username?.slice(0, 1)?.toUpperCase() || 'U' }}
            </span>
            <span class="account-name">{{ auth.user?.username || '当前账号' }}</span>
            <button
              class="icon-button"
              type="button"
              aria-label="退出登录"
              @click="logout"
            >
              <AppIcon name="logout" :size="17" />
            </button>
          </div>

          <button
            class="mobile-menu"
            type="button"
            aria-label="打开导航"
            :aria-expanded="mobileOpen"
            @click="mobileOpen = true"
          >
            <AppIcon name="menu" :size="20" />
          </button>
        </div>
      </div>
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
                <ArchiveMark :size="28" />
              </span>
              <span class="brand-copy">
                <strong>WebCollector</strong>
                <small>{{ currentTitle }}</small>
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
  background: var(--ground);
}

.skip-link {
  position: fixed;
  top: 8px;
  left: 8px;
  z-index: 100;
  padding: 10px 14px;
  border-radius: var(--radius-pill);
  color: var(--on-primary);
  background: var(--text);
  text-decoration: none;
  transform: translateY(-160%);
  transition: transform var(--motion-fast) var(--ease-out);
}

.skip-link:focus {
  transform: translateY(0);
}

.site-header {
  position: sticky;
  top: 0;
  z-index: 30;
  height: var(--topbar-height);
  border-bottom: 1px solid var(--border);
  background: var(--ground);
}

.header-inner {
  width: min(var(--content-width), calc(100% - (2 * var(--page-pad))));
  height: 100%;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(190px, 1fr) auto minmax(190px, 1fr);
  align-items: center;
  gap: 28px;
}

.brand {
  width: fit-content;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text);
  text-decoration: none;
}

.brand-mark {
  display: grid;
  place-items: center;
  color: var(--text);
}

.brand-copy {
  display: grid;
  gap: 0;
}

.brand-copy strong {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 500;
  letter-spacing: -0.02em;
  line-height: 1.2;
}

.brand-copy small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.35;
}

.desktop-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.nav-item {
  min-height: 40px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border-radius: var(--radius-pill);
  color: var(--muted);
  text-decoration: none;
  font-size: var(--font-size-small);
  transition:
    color var(--motion-fast) var(--ease-out),
    background-color var(--motion-fast) var(--ease-out);
}

.nav-item:hover {
  color: var(--text);
  background: var(--raised);
}

.nav-item.is-active {
  color: var(--text);
  background: var(--active);
}

.header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.account-cluster {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.account-avatar {
  width: 32px;
  height: 32px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text);
  background: var(--active);
  font-size: 12px;
  font-weight: 600;
}

.account-name {
  max-width: 110px;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-size-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy {
  min-width: 0;
  display: grid;
}

.account-copy strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy small {
  color: var(--muted);
  font-size: 11px;
}

.icon-button,
.mobile-menu,
.text-button {
  border: 0;
  color: var(--muted);
  background: transparent;
}

.icon-button {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
}

.icon-button:hover {
  color: var(--text);
  background: var(--raised);
}

.mobile-menu {
  width: 44px;
  height: 44px;
  display: none;
  place-items: center;
  border-radius: var(--radius-pill);
}

.app-main {
  min-width: 0;
}

.mobile-sheet {
  display: none;
}

@media (max-width: 1080px) {
  .header-inner {
    grid-template-columns: auto 1fr auto;
  }

  .account-name {
    display: none;
  }
}

@media (max-width: 900px) {
  .site-header {
    height: 56px;
  }

  .header-inner {
    width: calc(100% - 24px);
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 4px;
  }

  .brand-copy small {
    display: none;
  }

  .desktop-nav,
  .account-cluster {
    display: none;
  }

  .mobile-menu {
    display: grid;
  }

  .mobile-sheet {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: block;
    background: var(--overlay-scrim);
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
    min-height: 52px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .mobile-nav {
    margin-top: 26px;
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
    border-radius: var(--radius-pill);
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
