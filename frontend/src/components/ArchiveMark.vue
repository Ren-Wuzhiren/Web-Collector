<script setup>
defineProps({
  size: {
    type: Number,
    default: 32,
  },
})
</script>

<template>
  <svg
    class="archive-mark"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    aria-hidden="true"
  >
    <rect x="8.5" y="3.5" width="17" height="21" rx="2.5" stroke="currentColor" />
    <rect x="5.5" y="6.5" width="17" height="21" rx="2.5" fill="var(--ground)" stroke="currentColor" />
    <rect x="2.5" y="9.5" width="17" height="19" rx="2.5" fill="var(--surface)" stroke="currentColor" />
    <path d="M7 15H15M7 19H13M7 23H15" stroke="currentColor" stroke-linecap="round" />
  </svg>
</template>

<style scoped>
.archive-mark {
  display: block;
  flex: none;
  color: var(--text);
}
</style>
