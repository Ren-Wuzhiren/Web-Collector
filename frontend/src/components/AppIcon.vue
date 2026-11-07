<script setup>
defineProps({
  name: {
    type: String,
    required: true,
  },
  size: {
    type: [Number, String],
    default: 18,
  },
  strokeWidth: {
    type: [Number, String],
    default: 1.7,
  },
})
</script>

<template>
  <svg
    class="app-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="strokeWidth"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <g v-if="name === 'links'">
      <path d="M10.5 13.5l3-3" />
      <path d="M7.8 16.2l-1.1 1.1a3.5 3.5 0 0 1-5-5l3.2-3.2a3.5 3.5 0 0 1 5 0" />
      <path d="M16.2 7.8l1.1-1.1a3.5 3.5 0 1 1 5 5l-3.2 3.2a3.5 3.5 0 0 1-5 0" />
    </g>
    <g v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4.2 4.2" />
    </g>
    <g v-else-if="name === 'tag'">
      <path d="M3.8 5.6V3.8h8.7l7.7 7.7-8.7 8.7-7.7-7.7z" />
      <circle cx="8.1" cy="8.1" r="1.35" />
    </g>
    <g v-else-if="name === 'plus'">
      <path d="M12 5v14M5 12h14" />
    </g>
    <g v-else-if="name === 'refresh'">
      <path d="M20 11a8 8 0 1 0-2.3 5.7" />
      <path d="M20 4v7h-7" />
    </g>
    <g v-else-if="name === 'sun'">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </g>
    <g v-else-if="name === 'moon'">
      <path d="M20.2 15.4A8.5 8.5 0 0 1 8.6 3.8a8.5 8.5 0 1 0 11.6 11.6Z" />
    </g>
    <g v-else-if="name === 'grid'">
      <rect x="3.5" y="3.5" width="7" height="7" rx="1" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1" />
    </g>
    <g v-else-if="name === 'list'">
      <path d="M9 6h11M9 12h11M9 18h11" />
      <path d="M4 6h.01M4 12h.01M4 18h.01" />
    </g>
    <g v-else-if="name === 'external'">
      <path d="M14 4h6v6" />
      <path d="M20 4l-9 9" />
      <path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
    </g>
    <g v-else-if="name === 'chevron'">
      <path d="M9 5l7 7-7 7" />
    </g>
    <g v-else-if="name === 'menu'">
      <path d="M4 7h16M4 12h16M4 17h16" />
    </g>
    <g v-else-if="name === 'close'">
      <path d="M6 6l12 12M18 6L6 18" />
    </g>
    <g v-else-if="name === 'logout'">
      <path d="M10 5H5a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h5" />
      <path d="M14 8l4 4-4 4M8 12h10" />
    </g>
    <g v-else-if="name === 'alert'">
      <path d="M12 4l9 16H3z" />
      <path d="M12 9v4.5M12 17h.01" />
    </g>
    <g v-else-if="name === 'clock'">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7v5l3.2 2" />
    </g>
    <g v-else-if="name === 'archive'">
      <path d="M4 7.5h16" />
      <path d="M5.5 7.5v11.25h13V7.5" />
      <path d="M3 4h18v3.5H3z" />
      <path d="M9 11.5h6" />
    </g>
    <g v-else>
      <circle cx="12" cy="12" r="8.5" />
    </g>
  </svg>
</template>

<style scoped>
.app-icon {
  flex: none;
}
</style>
