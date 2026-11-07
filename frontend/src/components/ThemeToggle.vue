<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  getCurrentTheme,
  getStoredTheme,
  onThemeChange,
  toggleTheme,
} from '../utils/theme'

defineProps({
  compact: {
    type: Boolean,
    default: false,
  },
})

const theme = ref(getCurrentTheme() || getStoredTheme())
let stopListening = null

const isDark = computed(() => theme.value === 'dark')
const targetLabel = computed(() =>
  isDark.value ? '切换到浅色主题' : '切换到黑夜主题',
)

onMounted(() => {
  stopListening = onThemeChange((nextTheme) => {
    theme.value = nextTheme
  })
})

onBeforeUnmount(() => {
  stopListening?.()
})

function handleToggle() {
  theme.value = toggleTheme()
}
</script>

<template>
  <button
    class="theme-toggle"
    :class="{ 'is-compact': compact }"
    type="button"
    :aria-label="targetLabel"
    :aria-pressed="isDark"
    :title="targetLabel"
    @click="handleToggle"
  >
    <AppIcon :name="isDark ? 'sun' : 'moon'" :size="17" />
    <span v-if="!compact">{{ isDark ? '浅色' : '黑夜' }}</span>
  </button>
</template>

<style scoped>
.theme-toggle {
  min-height: var(--control-height);
  padding: 0 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--control);
  border-radius: var(--radius-pill);
  color: var(--muted);
  background: transparent;
  font-size: var(--font-size-small);
  font-weight: 500;
  transition:
    color var(--motion-fast) var(--ease-out),
    border-color var(--motion-fast) var(--ease-out),
    background-color var(--motion-fast) var(--ease-out);
}

.theme-toggle:hover {
  color: var(--text);
  border-color: var(--control-hover);
  background: var(--raised);
}

.theme-toggle.is-compact {
  width: 40px;
  min-height: 40px;
  padding: 0;
  border-color: transparent;
}

@media (max-width: 900px) {
  .theme-toggle {
    min-height: 44px;
  }
}
</style>
