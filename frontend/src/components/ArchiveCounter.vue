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
const motionState = ref('idle')
let motionSequence = 0

const text = computed(() => (typeof props.value === 'number' ? String(props.value) : '—'))

const digits = computed(() => {
  if (typeof props.value !== 'number') return []
  const value = String(props.value)
  return value.split('').map((digit, index) => ({
    digit: Number(digit),
    key: index - (value.length - 1),
  }))
})

function reelsIn(element) {
  return Array.from(element?.querySelectorAll('.digit-window') ?? [])
}

// [约定] 偏移用 em 表示：每个 reel 与其窗口字号相同，一位数字正好等于一个 em。
function placeReel(windowElement, position) {
  const reel = windowElement.querySelector('.digit-reel')
  if (reel) reel.style.transform = `translateY(${-position}em)`
}

function prefersReducedMotion() {
  return window.matchMedia(REDUCED_MOTION_QUERY).matches
}

function currentReels() {
  return new Map(
    reelsIn(root.value).map((windowElement) => [
      windowElement.dataset.digitKey,
      {
        reel: windowElement.querySelector('.digit-reel'),
        digit: Number(windowElement.querySelector('.digit-reel')?.dataset.digit),
      },
    ]),
  )
}

onMounted(() => {
  reelsIn(root.value).forEach((windowElement) => {
    placeReel(windowElement, Number(windowElement.dataset.digitValue))
  })
})

watch(
  () => props.value,
  async (next, previous) => {
    // [边界] pre 阶段 DOM 还是旧值，必须按从右数 key 记下每位旧数字。
    const previousReels = currentReels()
    await nextTick()

    const canRoll =
      typeof previous === 'number' && typeof next === 'number' && !prefersReducedMotion()
    const animations = []
    const sequence = ++motionSequence

    reelsIn(root.value).forEach((windowElement) => {
      const key = windowElement.dataset.digitKey
      const to = Number(windowElement.dataset.digitValue)
      const previousReel = previousReels.get(key)
      const from = previousReel?.digit
      // 新增的位数、数字没变的位数、减少动态效果时，直接放到终态。
      if (!canRoll || from === undefined || from === to) {
        placeReel(windowElement, to)
        return
      }

      // [行为] 数字始终正向滚动；9→0 先滚到末尾复制的 0，再无感复位到首位 0。
      const distance = (to - from + 10) % 10
      if (distance === 0) {
        placeReel(windowElement, to)
        return
      }

      const animation = animate(
        previousReel.reel,
        { transform: [`translateY(${-from}em)`, `translateY(${-(from + distance)}em)`] },
        { duration: ROLL_DURATION_SECONDS, ease: ROLL_EASE },
      )
      animations.push(animation.finished.catch(() => {}).then(() => {
        placeReel(windowElement, to)
      }))
    })

    if (!animations.length) {
      if (sequence === motionSequence) motionState.value = 'idle'
      return
    }

    motionState.value = 'rolling'
    await Promise.allSettled(animations)
    if (sequence === motionSequence) motionState.value = 'idle'
  },
)
</script>

<template>
  <span
    ref="root"
    class="archive-counter num"
    data-ui
    :data-motion-state="motionState"
  >
    <span class="visually-hidden">{{ text }}</span>
    <span v-if="digits.length" class="counter-digits" aria-hidden="true">
      <span
        v-for="item in digits"
        :key="item.key"
        class="digit-window"
        :data-digit-key="item.key"
        :data-digit-value="item.digit"
      >
        <span class="digit-reel" :data-digit="item.digit">
          <span
            v-for="n in 11"
            :key="n"
            :data-value="n - 1 === 10 ? 0 : n - 1"
          ></span>
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

.digit-reel > span::before {
  content: attr(data-value);
}
</style>
