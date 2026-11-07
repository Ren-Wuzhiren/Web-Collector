<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import api from '../api'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  open: {
    type: Boolean,
    default: false,
  },
  collections: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['update:open', 'saved'])
const saving = ref(false)
const submitError = ref('')
const urlInput = ref(null)
const form = reactive({
  url: '',
  collectionId: null,
  title: '',
  description: '',
})

const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const preferredCollectionId = computed(() => {
  const inbox = props.collections.find((item) => item.isInbox)
  return inbox?.id ?? props.collections[0]?.id ?? null
})

watch(
  () => props.collections,
  (collections) => {
    if (
      collections.length > 0 &&
      !collections.some((item) => item.id === form.collectionId)
    ) {
      form.collectionId = preferredCollectionId.value
    }
  },
  { immediate: true },
)

watch(
  () => props.open,
  async (open) => {
    if (!open) return

    submitError.value = ''
    if (!form.collectionId) {
      form.collectionId = preferredCollectionId.value
    }

    await nextTick()
    urlInput.value?.focus()
  },
)

async function submit() {
  submitError.value = ''
  const url = form.url.trim()

  if (!isHttpUrl(url)) {
    submitError.value = '请输入完整的 HTTP 或 HTTPS 网页地址。'
    urlInput.value?.focus()
    return
  }
  if (!form.collectionId) {
    submitError.value = '请选择一个集合。'
    return
  }

  saving.value = true
  try {
    const response = await api.post('/links', {
      url,
      collectionId: form.collectionId,
      title: form.title.trim(),
      description: form.description.trim(),
      tagIds: [],
    })

    emit('saved', response.data)
    resetForm()
    visible.value = false
  } catch (error) {
    submitError.value = error.message
  } finally {
    saving.value = false
  }
}

function isHttpUrl(value) {
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:'
  } catch {
    return false
  }
}

function resetForm() {
  form.url = ''
  form.title = ''
  form.description = ''
  form.collectionId = preferredCollectionId.value
  submitError.value = ''
}
</script>

<template>
  <el-drawer
    v-model="visible"
    class="save-drawer"
    direction="rtl"
    size="440px"
    :with-header="false"
    append-to-body
  >
    <div class="drawer-shell">
      <header class="drawer-head">
        <div>
          <h2>保存网页</h2>
          <p>先记下来源，再由后台生成正文与页面副本。</p>
        </div>
        <button
          class="drawer-close"
          type="button"
          aria-label="关闭保存窗口"
          @click="visible = false"
        >
          <AppIcon name="close" :size="20" />
        </button>
      </header>

      <form class="save-form" @submit.prevent="submit">
        <div class="field">
          <label for="save-url">网页 URL</label>
          <el-input
            id="save-url"
            ref="urlInput"
            v-model="form.url"
            :disabled="saving"
            autocomplete="url"
            placeholder="https://docs.spring.io/spring-boot/reference/"
          />
          <p class="field-hint">只接受 HTTP 或 HTTPS 地址。</p>
        </div>

        <div class="field">
          <label id="save-collection-label">集合</label>
          <el-select
            v-model="form.collectionId"
            :disabled="saving || collections.length === 0"
            placeholder="选择集合"
            aria-labelledby="save-collection-label"
          >
            <el-option
              v-for="collection in collections"
              :key="collection.id"
              :label="collection.name"
              :value="collection.id"
            />
          </el-select>
          <p v-if="collections.length === 0" class="field-error">
            集合目录尚未加载，请关闭窗口后重试。
          </p>
        </div>

        <div class="field">
          <label for="save-title">标题</label>
          <el-input
            id="save-title"
            v-model="form.title"
            :disabled="saving"
            placeholder="留空时使用网页标题"
          />
        </div>

        <div class="field">
          <label for="save-description">备注</label>
          <el-input
            id="save-description"
            v-model="form.description"
            type="textarea"
            :rows="4"
            :disabled="saving"
            placeholder="为什么值得留下这份内容？"
          />
        </div>

        <p v-if="submitError" class="form-error" role="alert">
          <AppIcon name="alert" :size="16" />
          <span>{{ submitError }}</span>
        </p>

        <div class="drawer-actions">
          <button class="secondary-button" type="button" @click="visible = false">
            取消
          </button>
          <button
            class="primary-button"
            type="submit"
            data-testid="save-submit"
            :disabled="saving || collections.length === 0"
          >
            {{ saving ? '正在创建任务…' : '保存网页' }}
          </button>
        </div>
      </form>

      <div class="drawer-note">
        <AppIcon name="clock" :size="17" />
        <p>保存成功表示抓取任务已排队；完成状态会在收藏页持续更新。</p>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-shell {
  min-height: 100%;
  padding: 26px 26px 24px;
  display: flex;
  flex-direction: column;
}

.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 22px;
  border-bottom: 1px solid var(--border);
}

.drawer-head h2 {
  margin: 0;
  font-size: 20px;
  line-height: 1.3;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.drawer-head p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.drawer-close {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--muted);
  background: transparent;
}

.drawer-close:hover {
  color: var(--text);
  background: var(--raised);
}

.save-form {
  padding: 24px 0 18px;
  display: grid;
  gap: 20px;
}

.field {
  display: grid;
  gap: 8px;
}

.field label {
  color: var(--text);
  font-size: 13px;
  font-weight: 550;
}

.field-hint,
.field-error {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
}

.field-hint {
  color: var(--muted);
}

.field-error,
.form-error {
  color: var(--negative);
}

.form-error {
  margin: 0;
  padding: 10px 12px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  border-radius: var(--radius-control);
  background: var(--negative-soft);
  font-size: 12px;
  line-height: 1.5;
}

.drawer-actions {
  padding-top: 4px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.primary-button,
.secondary-button {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-control);
  font-weight: 550;
  transition:
    background-color var(--motion-fast) var(--ease-out),
    border-color var(--motion-fast) var(--ease-out);
}

.primary-button {
  border: 1px solid var(--text);
  color: var(--surface);
  background: var(--text);
}

.primary-button:hover {
  background: var(--text-hover);
}

.primary-button:disabled {
  border-color: var(--control);
  color: var(--muted);
  background: var(--active);
  cursor: not-allowed;
}

.secondary-button {
  border: 1px solid var(--border);
  color: var(--text);
  background: var(--surface);
}

.secondary-button:hover {
  background: var(--raised);
}

.drawer-note {
  margin-top: auto;
  padding: 14px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  border-radius: var(--radius-panel);
  color: var(--muted);
  background: var(--raised);
}

.drawer-note p {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 520px) {
  .drawer-shell {
    padding: 20px 18px;
  }

  .drawer-actions > button {
    min-height: 44px;
    flex: 1;
  }
}
</style>
