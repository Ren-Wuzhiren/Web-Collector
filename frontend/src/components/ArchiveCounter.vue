<script setup>
import { animate } from 'motion-v'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

// [设计] 数字只在用户动作改变数值后滚动一次；首次挂载和“—”变成数字都直接显示。
// motion 不读取 CSS 变量，所以时长和缓动与 tokens.css 的 --motion-standard / --ease-out 手动保持一致。
const ROLL_DURATION_SECONDS = 0.22
const ROLL_EASE = [0.2, 0.8, 0.2, 1]
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

const props = defineProps({
  value: {
    type: Number,
    default: null,
  },
})

const root = ref(null)

const text = computed(() => (typeof props.value === 'number' ? String(props.value) : '—'))

const digits = computed(() => {
  if (typeof props.value !== 'number') return []
  return String(props.value).split('').map(Number)
})

function reelsIn(element) {
  return Array.from(element?.querySelectorAll('.digit-reel') ?? [])
}

// [约定] 偏移用 em 表示：每个 reel 与其窗口字号相同，一位数字正好等于一个 em。
function placeReel(reel, digit) {
  reel.style.transform = `translateY(${-digit}em)`
}

function prefersReducedMotion() {
  return window.matchMedia(REDUCED_MOTION_QUERY).matches
}

onMounted(() => {
  reelsIn(root.value).forEach((reel) => placeReel(reel, Number(reel.dataset.digit)))
})

watch(
  () => props.value,
  async (next, previous) => {
    // [边界] pre 阶段 DOM 还是旧值，必须在这里记下每一位的旧数字；await nextTick 之后就读不到了。
    const fromDigits = reelsIn(root.value).map((reel) => Number(reel.dataset.digit))
    await nextTick()

    const canRoll =
      typeof previous === 'number' && typeof next === 'number' && !prefersReducedMotion()

    reelsIn(root.value).forEach((reel, index) => {
      const to = Number(reel.dataset.digit)
      const from = fromDigits[index]
      // 新增的位数、数字没变的位数、减少动态效果时，直接放到终态。
      if (!canRoll || from === undefined || from === to) {
        placeReel(reel, to)
        return
      }
      animate(
        reel,
        { transform: [`translateY(${-from}em)`, `translateY(${-to}em)`] },
        { duration: ROLL_DURATION_SECONDS, ease: ROLL_EASE },
      )
    })
  },
)
</script>

<template>
  <span ref="root" class="archive-counter num">
    <span class="visually-hidden">{{ text }}</span>
    <span v-if="digits.length" class="counter-digits" aria-hidden="true">
      <span v-for="(digit, index) in digits" :key="index" class="digit-window">
        <span class="digit-reel" :data-digit="digit">
          <span v-for="n in 10" :key="n">{{ n - 1 }}</span>
        </span>
      </span>
    </span>
    <span v-else class="counter-empty" aria-hidden="true">—</span>
  </span>
</template>

<style scoped>
.archive-counter {
  position: relative;
  display: inline-block;
  line-height: 1;
  white-space: nowrap;
}

.counter-digits {
  display: inline-flex;
}

.digit-window {
  height: 1em;
  display: inline-block;
  overflow: hidden;
  vertical-align: top;
}

.digit-reel {
  display: block;
}

.digit-reel > span {
  display: block;
  height: 1em;
}
</style>
