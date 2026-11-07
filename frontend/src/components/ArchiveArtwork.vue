<script setup>
import { computed } from 'vue'

const props = defineProps({
  linkId: {
    type: [Number, String],
    default: 0,
  },
  title: {
    type: String,
    default: '',
  },
  domain: {
    type: String,
    default: '',
  },
})

const variants = [
  {
    tone: 'var(--art-peach)',
    label: 'archived sheets',
  },
  {
    tone: 'var(--art-coral)',
    label: 'captured window',
  },
  {
    tone: 'var(--art-blue)',
    label: 'linked pages',
  },
  {
    tone: 'var(--art-lavender)',
    label: 'open references',
  },
  {
    tone: 'var(--art-mist)',
    label: 'browser evidence',
  },
  {
    tone: 'var(--art-sage)',
    label: 'archive stack',
  },
]

const variantIndex = computed(() => {
  const seed = `${props.linkId}:${props.domain}:${props.title}`
  let hash = 2166136261

  for (let index = 0; index < seed.length; index += 1) {
    hash ^= seed.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }

  return Math.abs(hash) % variants.length
})

const variant = computed(() => variants[variantIndex.value])
</script>

<template>
  <figure
    class="archive-art"
    :style="{ '--art-surface': variant.tone }"
    :data-art-variant="variantIndex"
    :aria-label="`${variant.label} illustration`"
  >
    <svg viewBox="0 0 320 184" fill="none" aria-hidden="true">
      <g
        v-if="variantIndex === 0"
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M48 75h34v40H48z" fill="var(--art-surface)" />
        <path d="M62 59h112l24 22v62H62z" fill="var(--art-surface)" />
        <path d="M174 59v23h24M84 93h90M84 109h76M84 125h52" />
        <path d="M212 88c14 0 24 10 24 24s-10 24-24 24-24-10-24-24 10-24 24-24Z" />
        <path d="m229 129 20 20" />
      </g>

      <g
        v-else-if="variantIndex === 1"
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <rect x="76" y="48" width="168" height="96" rx="4" fill="var(--art-surface)" />
        <path d="M76 64h168M91 56h.01M104 56h.01M117 56h.01" />
        <path d="M101 87h78M101 104h116M101 121h60" />
        <path d="M208 94v28h20" />
      </g>

      <g
        v-else-if="variantIndex === 2"
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <circle cx="122" cy="92" r="31" fill="var(--art-surface)" />
        <circle cx="208" cy="62" r="18" fill="var(--art-surface)" />
        <circle cx="221" cy="130" r="20" fill="var(--art-surface)" />
        <path d="m150 78 40-10M149 106l49 17M205 79l11 31" />
        <path d="M108 78c14 10 14 18 0 28M136 72c-14 12-14 28 0 40" />
      </g>

      <g
        v-else-if="variantIndex === 3"
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M76 60h70c9 0 16 7 16 16v60H92c-9 0-16-7-16-16V60Z" fill="var(--art-surface)" />
        <path d="M244 60h-70c-9 0-16 7-16 16v60h70c9 0 16-7 16-16V60Z" fill="var(--art-surface)" />
        <path d="M162 76v60M92 82h48M92 98h36M180 82h48M180 98h36" />
      </g>

      <g
        v-else-if="variantIndex === 4"
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <rect x="90" y="52" width="140" height="92" rx="5" fill="var(--art-surface)" />
        <path d="M90 72h140M106 62h.01M119 62h.01M132 62h.01" />
        <path d="m112 112 22-22 18 16 15-19 30 30" />
        <circle cx="153" cy="99" r="4" fill="var(--art-ink)" />
      </g>

      <g
        v-else
        stroke="var(--art-ink)"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M76 126c30-22 48-54 66-76l58 52c18 16 34 12 44 8" fill="none" />
        <path d="M62 119h40v31H62zM217 79h42v49h-42z" fill="var(--art-surface)" />
        <path d="M72 131h20M230 94h17M230 108h14M230 121h20" />
        <circle cx="142" cy="50" r="8" fill="var(--art-surface)" />
        <circle cx="230" cy="73" r="7" fill="var(--art-surface)" />
      </g>
    </svg>
  </figure>
</template>

<style scoped>
.archive-art {
  aspect-ratio: 16 / 9;
  margin: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--art-surface);
}

.archive-art svg {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
