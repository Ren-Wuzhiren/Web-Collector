<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './components/AppShell.vue'

const route = useRoute()
const usesAppShell = computed(
  () => route.path !== '/login' && !route.meta.public,
)
</script>

<template>
  <AppShell v-if="usesAppShell">
    <router-view />
  </AppShell>
  <router-view v-else />
</template>
