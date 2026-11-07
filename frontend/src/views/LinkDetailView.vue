<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useCatalogStore } from '../stores/catalog'

const route = useRoute()
const router = useRouter()
const catalog = useCatalogStore()

const link = ref(null)
const readableText = ref('')
const assets = ref([])
const linkTags = ref([])
const loading = ref(true)
const fatalError = ref('')
const readableState = reactive({ loading: false, missing: false, error: '' })
const assetsState = reactive({ loading: false, error: '' })
const tagsState = reactive({ loading: false, error: '' })
const job = ref(null)
const jobState = reactive({ loading: false, error: '' })
const editingTags = ref(false)
const selectedTagIds = ref([])
const savingTags = ref(false)
const editOpen = ref(false)
const savingMetadata = ref(false)
const screenshotUrl = ref('')

const editForm = reactive({
  title: '',
  description: '',
  collectionId: null,
})

const temporaryUrls = new Set()
let jobTimer = null
let requestSequence = 0

const formatInfo = {
  HTML: {
    label: 'HTML',
    title: '完整网页',
    description: '保存当时页面结构与资源的离线副本',
    action: '下载',
  },
  PDF: {
    label: 'PDF',
    title: '打印副本',
    description: '用于长期保存和跨设备阅读的版式副本',
    action: '打开',
  },
  SCREENSHOT: {
    label: 'PNG',
    title: '页面截图',
    description: '保存页面当时可见内容与视觉状态',
    action: '预览',
  },
}

const domain = computed(() => {
  if (!link.value?.originalUrl) return ''
  try {
    return new URL(link.value.originalUrl).hostname
  } catch {
    return link.value.originalUrl
  }
})

const displayTitle = computed(() => link.value?.title?.trim() || domain.value || '未命名网页')

const evidenceRows = computed(() =>
  ['HTML', 'PDF', 'SCREENSHOT'].map((format) => {
    const asset = assets.value.find((item) => item.format === format)
    return {
      format,
      asset,
      ...formatInfo[format],
    }
  }),
)

const evidenceCount = computed(() => evidenceRows.value.filter((item) => item.asset).length)

const readiness = computed(() => {
  if (readableState.loading) {
    return { label: '正在读取正文', tone: 'muted' }
  }
  if (readableText.value) {
    return { label: '正文可读', tone: 'positive' }
  }
  return { label: '正文尚未生成', tone: 'warning' }
})

const jobStatusText = computed(() => {
  const labels = {
    PENDING: '已排队，等待保存',
    RUNNING: '正在保存这个网页',
    SUCCEEDED: '网页保存完成',
    FAILED: '保存没有完成',
  }
  return job.value ? labels[job.value.status] || job.value.status : ''
})

// 三段式进度：已排队 → 正在保存 → 结果（完成或失败）。只表达状态，不做循环动画。
const jobSteps = computed(() => {
  const status = job.value?.status
  if (!status) return []
  const currentIndex = status === 'PENDING' ? 0 : status === 'RUNNING' ? 1 : 2
  const steps = [
    { key: 'queued', label: '已排队' },
    { key: 'running', label: '正在保存' },
    status === 'FAILED'
      ? { key: 'failed', label: '保存失败' }
      : { key: 'succeeded', label: '保存完成' },
  ]
  return steps.map((step, index) => {
    let state = 'pending'
    if (index < currentIndex) state = 'done'
    else if (index === currentIndex) {
      if (status === 'FAILED') state = 'error'
      else if (status === 'SUCCEEDED') state = 'done'
      else state = 'current'
    }
    return { ...step, state }
  })
})

// 任务从 PENDING/RUNNING 进入终态时置为 true，只用于触发正文与证据的淡入。
// 首次打开已经完成的收藏时保持 false，不播放动效。
const jobJustSettled = ref(false)

const canEditTags = computed(() => !tagsState.loading && !tagsState.error)

watch(
  () => route.params.id,
  () => loadDetail(),
  { immediate: true },
)

onBeforeUnmount(() => {
  stopJobPolling()
  revokeScreenshot()
  for (const url of temporaryUrls) URL.revokeObjectURL(url)
  temporaryUrls.clear()
})

async function loadDetail() {
  const sequence = ++requestSequence
  const linkId = Number(route.params.id)

  stopJobPolling()
  revokeScreenshot()
  link.value = null
  readableText.value = ''
  assets.value = []
  linkTags.value = []
  job.value = null
  jobJustSettled.value = false
  loading.value = true
  fatalError.value = ''
  readableState.error = ''
  assetsState.error = ''
  tagsState.error = ''
  jobState.error = ''
  editingTags.value = false
  editOpen.value = false

  if (!Number.isSafeInteger(linkId) || linkId <= 0) {
    fatalError.value = '链接编号无效。'
    loading.value = false
    return
  }

  try {
    const response = await api.get(`/links/${linkId}`)
    if (sequence !== requestSequence) return
    link.value = response.data
    resetEditForm()
  } catch (error) {
    if (sequence !== requestSequence) return
    fatalError.value =
      error.status === 404
        ? '没有找到这条收藏。它可能已被删除，或者不属于当前账号。'
        : error.message
    loading.value = false
    return
  }

  loading.value = false
  await Promise.allSettled([
    loadReadable(linkId, sequence),
    loadAssets(linkId, sequence),
    loadLinkTags(linkId, sequence),
    loadCatalogs(sequence),
    loadJobFromQuery(linkId, sequence),
  ])
}

async function loadReadable(linkId, sequence = requestSequence) {
  if (sequence !== requestSequence) return
  readableState.loading = true
  readableState.missing = false
  readableState.error = ''
  try {
    const response = await api.get(`/links/${linkId}/readable`)
    if (sequence !== requestSequence) return
    readableText.value = response.data?.textContent?.trim() || ''
    readableState.missing = !readableText.value
  } catch (error) {
    if (sequence !== requestSequence) return
    if (error.status === 404) {
      readableText.value = ''
      readableState.missing = true
      return
    }
    readableState.error = error.message
  } finally {
    if (sequence === requestSequence) {
      readableState.loading = false
    }
  }
}

async function loadAssets(linkId, sequence = requestSequence) {
  if (sequence !== requestSequence) return
  assetsState.loading = true
  assetsState.error = ''
  try {
    const response = await api.get('/assets', { params: { linkId } })
    if (sequence !== requestSequence) return
    assets.value = response.data
    await prepareScreenshotPreview(sequence)
  } catch (error) {
    if (sequence !== requestSequence) return
    assetsState.error = error.message
  } finally {
    if (sequence === requestSequence) {
      assetsState.loading = false
    }
  }
}

async function loadLinkTags(linkId, sequence = requestSequence) {
  if (sequence !== requestSequence) return
  tagsState.loading = true
  tagsState.error = ''
  try {
    const response = await api.get(`/links/${linkId}/tags`)
    if (sequence !== requestSequence) return
    linkTags.value = response.data
    selectedTagIds.value = response.data.map((tag) => tag.id)
  } catch (error) {
    if (sequence !== requestSequence) return
    tagsState.error =
      error.status === 404
        ? '标签当前不可见，正文与保存证据不受影响。'
        : error.message
  } finally {
    if (sequence === requestSequence) {
      tagsState.loading = false
    }
  }
}

async function loadCatalogs(sequence = requestSequence) {
  await Promise.allSettled([
    catalog.refreshCollections(),
    catalog.refreshTags(),
  ])
  if (sequence !== requestSequence) return
}

async function loadJobFromQuery(linkId, sequence = requestSequence) {
  const jobId = Number(route.query.job)
  if (!Number.isSafeInteger(jobId) || jobId <= 0) return
  await fetchJob(jobId, linkId, sequence, true)
}

async function fetchJob(jobId, linkId, sequence, scheduleNext = false) {
  jobState.loading = !job.value
  jobState.error = ''
  try {
    const response = await api.get(`/jobs/${jobId}`)
    if (sequence !== requestSequence) return
    if (response.data.linkId !== linkId) {
      throw new Error('抓取任务不属于当前收藏。')
    }
    const previousStatus = job.value?.status
    job.value = response.data
    const terminal = ['SUCCEEDED', 'FAILED'].includes(job.value.status)
    if (terminal) {
      stopJobPolling()
      if (previousStatus === 'PENDING' || previousStatus === 'RUNNING') {
        jobJustSettled.value = true
      }
      await Promise.allSettled([
        loadReadable(linkId, sequence),
        loadAssets(linkId, sequence),
      ])
      if (route.query.job) {
        const nextQuery = { ...route.query }
        delete nextQuery.job
        await router.replace({ query: nextQuery })
      }
      return
    }
    if (scheduleNext) {
      jobTimer = window.setTimeout(
        () => fetchJob(jobId, linkId, sequence, true),
        3000,
      )
    }
  } catch (error) {
    if (sequence !== requestSequence) return
    jobState.error = error.message
    stopJobPolling()
  } finally {
    if (sequence === requestSequence) {
      jobState.loading = false
    }
  }
}

async function prepareScreenshotPreview(sequence) {
  revokeScreenshot()
  const screenshot = assets.value.find((asset) => asset.format === 'SCREENSHOT')
  if (!screenshot) return
  try {
    const blob = await api.get(`/assets/${screenshot.id}`, { responseType: 'blob' })
    if (sequence !== requestSequence) return
    screenshotUrl.value = URL.createObjectURL(blob)
  } catch {
    if (sequence === requestSequence) {
      screenshotUrl.value = ''
    }
  }
}

async function openAsset(asset) {
  try {
    const blob = await api.get(`/assets/${asset.id}`, { responseType: 'blob' })
    if (asset.format === 'HTML') {
      downloadBlob(blob, `${safeFilename(displayTitle.value)}.html`)
      return
    }
    const url = URL.createObjectURL(blob)
    temporaryUrls.add(url)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.target = '_blank'
    anchor.rel = 'noopener noreferrer'
    anchor.click()
    window.setTimeout(() => {
      URL.revokeObjectURL(url)
      temporaryUrls.delete(url)
    }, 60000)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function safeFilename(value) {
  return value.replace(/[\\/:*?"<>|]/g, '-').slice(0, 80) || 'webcollector-snapshot'
}

function revokeScreenshot() {
  if (screenshotUrl.value) {
    URL.revokeObjectURL(screenshotUrl.value)
    screenshotUrl.value = ''
  }
}

function stopJobPolling() {
  if (jobTimer) {
    window.clearTimeout(jobTimer)
    jobTimer = null
  }
}

function resetEditForm() {
  editForm.title = link.value?.title || ''
  editForm.description = link.value?.description || ''
  editForm.collectionId = link.value?.collectionId || null
}

function openEdit() {
  resetEditForm()
  editOpen.value = true
}

async function saveMetadata() {
  if (!editForm.collectionId) {
    ElMessage.warning('请选择所属集合')
    return
  }
  savingMetadata.value = true
  try {
    const response = await api.patch(`/links/${link.value.id}`, {
      collectionId: editForm.collectionId,
      title: editForm.title,
      description: editForm.description,
    })
    link.value = response.data
    editOpen.value = false
    await catalog.refreshCollections(true)
    ElMessage.success('收藏信息已更新')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    savingMetadata.value = false
  }
}

function startTagEditing() {
  selectedTagIds.value = linkTags.value.map((tag) => tag.id)
  editingTags.value = true
}

function cancelTagEditing() {
  selectedTagIds.value = linkTags.value.map((tag) => tag.id)
  editingTags.value = false
}

async function saveTags() {
  savingTags.value = true
  try {
    await api.put(`/links/${link.value.id}/tags`, { tagIds: selectedTagIds.value })
    await loadLinkTags(link.value.id)
    await catalog.refreshTags(true)
    editingTags.value = false
    ElMessage.success('标签已更新')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    savingTags.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section class="detail-page">
    <div v-if="loading" class="detail-skeleton" aria-label="正在加载链接详情">
      <div class="skeleton-line skeleton-back"></div>
      <div class="skeleton-line skeleton-title"></div>
      <div class="skeleton-line skeleton-copy"></div>
      <div class="skeleton-grid">
        <div class="skeleton-panel skeleton-reading"></div>
        <div class="skeleton-stack">
          <div class="skeleton-panel"></div>
          <div class="skeleton-panel skeleton-panel-small"></div>
        </div>
      </div>
    </div>

    <div v-else-if="fatalError" class="recovery-panel">
      <div class="recovery-copy">
        <p class="recovery-label">链接详情</p>
        <h1>{{ fatalError.includes('没有找到') ? '没有找到这条收藏' : '链接详情没有加载出来' }}</h1>
        <p>{{ fatalError }}</p>
        <div class="recovery-actions">
          <el-button type="primary" @click="router.push('/links')">返回收藏</el-button>
          <el-button @click="loadDetail">重试</el-button>
        </div>
      </div>
      <div class="recovery-context">
        <h2>这条链接可能已经不可见</h2>
        <p>收藏可能已被删除，或者它属于另一个账号。系统不会显示无权查看的标题、标签或保存证据。</p>
      </div>
    </div>

    <template v-else-if="link">
      <button type="button" class="back-link" @click="router.push('/links')">
        <span aria-hidden="true">←</span>
        返回收藏
      </button>

      <header class="detail-header">
        <div class="title-block">
          <div class="source-line">
            <span>{{ domain }}</span>
            <span aria-hidden="true">·</span>
            <span>{{ catalog.collectionName(link.collectionId) }}</span>
          </div>
          <h1>{{ displayTitle }}</h1>
          <p v-if="link.description" class="detail-description">{{ link.description }}</p>
          <div class="readiness" :class="`is-${readiness.tone}`">
            <span class="readiness-mark" aria-hidden="true"></span>
            <span>{{ readiness.label }}</span>
            <span class="readiness-separator" aria-hidden="true">·</span>
            <span>已保存 {{ evidenceCount }}/3 项证据</span>
          </div>
        </div>
        <div class="header-actions">
          <a
            class="secondary-action"
            :href="link.originalUrl"
            target="_blank"
            rel="noopener noreferrer"
          >
            打开原文
          </a>
          <button type="button" class="primary-action" @click="openEdit">
            编辑信息
          </button>
        </div>
      </header>

      <div class="detail-layout">
        <article class="reading-panel" aria-labelledby="reading-heading">
          <header class="panel-heading reading-heading">
            <div>
              <h2 id="reading-heading">可读正文</h2>
              <p>从保存的网页中清理出的纯文本，不执行原页面脚本。</p>
            </div>
            <span class="reading-count">{{ readableText.length.toLocaleString('zh-CN') }} 字符</span>
          </header>

          <div v-if="readableState.loading" class="reading-state">
            <div class="skeleton-line skeleton-copy"></div>
            <div class="skeleton-line skeleton-copy"></div>
            <div class="skeleton-line skeleton-copy skeleton-copy-short"></div>
          </div>
          <div v-else-if="readableState.error" class="inline-error">
            <strong>正文没有加载出来</strong>
            <p>{{ readableState.error }}</p>
            <button type="button" class="text-action" @click="loadReadable(link.id)">重新读取</button>
          </div>
          <div v-else-if="readableState.missing || !readableText" class="reading-empty">
            <h3>正文尚未生成</h3>
            <p>你仍可打开已有的页面截图或 PDF，回看保存时的内容。</p>
          </div>
          <p
            v-else
            class="readable-text"
            :class="{ 'is-settling': jobJustSettled }"
            data-testid="readable-text"
          >
            {{ readableText }}
          </p>
        </article>

        <aside class="evidence-rail" aria-label="保存证据与管理信息">
          <section class="side-panel evidence-panel" aria-labelledby="evidence-heading">
            <header class="panel-heading">
              <div>
                <h2 id="evidence-heading">保存证据</h2>
                <p>{{ evidenceCount }}/3 项已经生成</p>
              </div>
            </header>

            <div v-if="assetsState.loading" class="evidence-loading">
              <div v-for="index in 3" :key="index" class="skeleton-line skeleton-evidence"></div>
            </div>
            <div v-else-if="assetsState.error" class="inline-error compact-error">
              <strong>证据列表没有加载出来</strong>
              <p>{{ assetsState.error }}</p>
              <button type="button" class="text-action" @click="loadAssets(link.id)">重试</button>
            </div>
            <template v-else>
              <figure
                v-if="screenshotUrl"
                class="screenshot-preview"
                data-testid="screenshot-preview"
              >
                <img :src="screenshotUrl" :alt="`${displayTitle} 的页面截图`" />
                <figcaption>保存时的页面截图</figcaption>
              </figure>
              <div class="evidence-list" :class="{ 'is-settling': jobJustSettled }">
                <div
                  v-for="(row, index) in evidenceRows"
                  :key="row.format"
                  class="evidence-row"
                  :class="{ 'is-missing': !row.asset }"
                  :style="{ '--row-index': index }"
                  :data-testid="`evidence-${row.format.toLowerCase()}`"
                >
                  <span class="evidence-format">{{ row.label }}</span>
                  <div class="evidence-copy">
                    <strong>{{ row.title }}</strong>
                    <span v-if="row.asset">
                      {{ formatBytes(row.asset.fileSizeBytes) }} ·
                      {{ formatDate(row.asset.createdAt) }}
                    </span>
                    <span v-else>{{ row.description }}</span>
                  </div>
                  <button
                    v-if="row.asset"
                    type="button"
                    class="text-action evidence-action"
                    @click="openAsset(row.asset)"
                  >
                    {{ row.action }}
                  </button>
                  <span v-else class="missing-label">不可用</span>
                </div>
              </div>
              <p v-if="!evidenceCount" class="panel-note">
                快照尚未生成。抓取完成后，HTML、PDF 和截图会出现在这里。
              </p>
            </template>
          </section>

          <section class="side-panel" aria-labelledby="tags-heading">
            <header class="panel-heading compact-heading">
              <div>
                <h2 id="tags-heading">标签</h2>
                <p>{{ linkTags.length ? `${linkTags.length} 个标签` : '整理这条收藏' }}</p>
              </div>
              <button
                v-if="!editingTags && canEditTags"
                type="button"
                class="text-action"
                @click="startTagEditing"
              >
                编辑
              </button>
            </header>

            <div v-if="tagsState.loading" class="tag-loading">
              <span class="skeleton-chip"></span>
              <span class="skeleton-chip"></span>
            </div>
            <div v-else-if="tagsState.error" class="inline-error compact-error">
              <strong>标签暂时无法读取</strong>
              <p>{{ tagsState.error }}</p>
              <button type="button" class="text-action" @click="loadLinkTags(link.id)">重试</button>
            </div>
            <template v-else-if="editingTags">
              <el-select
                v-model="selectedTagIds"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="选择标签"
                class="tag-select"
              >
                <el-option
                  v-for="tag in catalog.tags"
                  :key="tag.id"
                  :label="tag.name"
                  :value="tag.id"
                />
              </el-select>
              <div class="tag-actions">
                <el-button size="small" @click="cancelTagEditing">取消</el-button>
                <el-button size="small" type="primary" :loading="savingTags" @click="saveTags">
                  保存
                </el-button>
              </div>
            </template>
            <div v-else-if="linkTags.length" class="tag-list">
              <span v-for="tag in linkTags" :key="tag.id" class="tag-chip">{{ tag.name }}</span>
            </div>
            <p v-else class="panel-note">未添加标签。</p>
          </section>

          <section class="side-panel" aria-labelledby="save-info-heading">
            <header class="panel-heading compact-heading">
              <div>
                <h2 id="save-info-heading">保存信息</h2>
              </div>
            </header>
            <dl class="metadata-list">
              <div>
                <dt>所属集合</dt>
                <dd>{{ catalog.collectionName(link.collectionId) }}</dd>
              </div>
              <div>
                <dt>首次保存</dt>
                <dd>{{ formatDate(link.createdAt) }}</dd>
              </div>
              <div>
                <dt>最近更新</dt>
                <dd>{{ formatDate(link.updatedAt) }}</dd>
              </div>
              <div>
                <dt>来源</dt>
                <dd class="source-url">{{ link.originalUrl }}</dd>
              </div>
            </dl>
          </section>

          <section
            v-if="job || jobState.loading || jobState.error"
            class="side-panel job-panel"
            aria-labelledby="job-heading"
          >
            <header class="panel-heading compact-heading">
              <div>
                <h2 id="job-heading">当前任务</h2>
                <p>{{ jobStatusText || '读取抓取进度' }}</p>
              </div>
            </header>
            <div v-if="jobState.loading" class="skeleton-line skeleton-job"></div>
            <div v-else-if="jobState.error" class="inline-error compact-error">
              <p>{{ jobState.error }}</p>
              <button type="button" class="text-action" @click="loadJobFromQuery(link.id)">
                重试
              </button>
            </div>
            <template v-else-if="job">
              <div
                class="job-status"
                :class="{
                  'is-active': ['PENDING', 'RUNNING'].includes(job.status),
                  'is-success': job.status === 'SUCCEEDED',
                  'is-error': job.status === 'FAILED',
                }"
                data-testid="job-status"
                :data-status="job.status"
                role="status"
                aria-live="polite"
              >
                <strong>{{ job.status }}</strong>
                <span>已尝试 {{ job.attemptCount }}/{{ job.maxAttempts }} 次</span>
                <ol class="job-steps" aria-label="保存进度">
                  <li
                    v-for="step in jobSteps"
                    :key="step.key"
                    class="job-step"
                    :class="`is-${step.state}`"
                    :data-step="step.key"
                  >
                    <span class="job-step-mark" aria-hidden="true"></span>
                    <span class="job-step-label">{{ step.label }}</span>
                  </li>
                </ol>
              </div>
              <p v-if="job.errorMessage" class="job-error">{{ job.errorMessage }}</p>
              <p v-else-if="['PENDING', 'RUNNING'].includes(job.status)" class="panel-note">
                生成完成后，正文和证据会自动刷新。
              </p>
            </template>
          </section>
        </aside>
      </div>
    </template>

    <el-dialog v-model="editOpen" title="编辑收藏信息" width="min(560px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="saveMetadata">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="所属集合" required>
          <el-select v-model="editForm.collectionId" placeholder="选择集合" class="full-width">
            <el-option
              v-for="collection in catalog.collections"
              :key="collection.id"
              :label="collection.name"
              :value="collection.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingMetadata" @click="saveMetadata">
          保存修改
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.detail-page {
  width: 100%;
  max-width: calc(var(--content-width) + (var(--page-pad) * 2));
  margin: 0 auto;
  padding: 32px var(--page-pad) 64px;
}

.back-link,
.text-action,
.secondary-action,
.primary-action {
  min-height: 36px;
  border-radius: var(--radius-control);
  font-size: 14px;
  cursor: pointer;
  transition:
    background-color 160ms ease,
    border-color 160ms ease,
    color 160ms ease;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: -8px 0 20px;
  padding: 4px 8px;
  border: 0;
  color: var(--muted);
  background: transparent;
}

.back-link:hover {
  color: var(--text);
  background: var(--active);
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 24px;
}

.title-block {
  min-width: 0;
}

.source-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
}

h1 {
  max-width: 820px;
  margin: 8px 0 0;
  font-size: 24px;
  font-weight: 650;
  line-height: 32px;
  letter-spacing: -0.02em;
  text-wrap: balance;
}

.detail-description {
  max-width: 72ch;
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.55;
}

.readiness {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  color: var(--muted);
  font-size: 13px;
}

.readiness-mark {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--control);
}

.readiness.is-positive .readiness-mark {
  background: var(--positive);
}

.readiness.is-warning .readiness-mark {
  background: var(--warning);
}

.readiness-separator {
  color: var(--control);
}

.header-actions {
  display: flex;
  flex: none;
  gap: 8px;
}

.secondary-action,
.primary-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  text-decoration: none;
}

.secondary-action {
  border: 1px solid var(--control);
  color: var(--text);
  background: var(--surface);
}

.secondary-action:hover {
  background: var(--raised);
}

.primary-action {
  border: 1px solid var(--text);
  color: var(--surface);
  background: var(--text);
}

.primary-action:hover {
  background: var(--text);
}

.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
  gap: var(--gap);
  align-items: start;
}

.reading-panel,
.side-panel,
.recovery-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.reading-panel {
  min-height: 560px;
  padding: 32px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-heading h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
}

.panel-heading p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.reading-heading {
  margin-bottom: 28px;
}

.reading-count {
  flex: none;
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.readable-text {
  max-width: 68ch;
  min-height: 120px;
  margin: 0;
  color: var(--text);
  font-family: var(--font-reading);
  font-size: 18px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 骨架与正文共用最小高度，正文落位时高度不跳变；动效只改 opacity，不改位置 */
.reading-state {
  min-height: 120px;
  display: grid;
  gap: 16px;
}

.reading-empty {
  display: grid;
  align-content: center;
  min-height: 400px;
  padding: 32px;
  border-radius: var(--radius-control);
  background: var(--raised);
  text-align: center;
}

.reading-empty h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.reading-empty p {
  max-width: 46ch;
  margin: 8px auto 0;
  color: var(--muted);
}

.evidence-rail {
  display: grid;
  gap: var(--gap);
}

.side-panel {
  padding: 20px;
}

.compact-heading {
  align-items: center;
}

.screenshot-preview {
  margin: 18px 0 16px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--raised);
}

.screenshot-preview img {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 10;
  object-fit: cover;
  object-position: top;
}

.screenshot-preview figcaption {
  padding: 8px 10px;
  color: var(--muted);
  font-size: 12px;
}

.evidence-list {
  margin-top: 16px;
}

.evidence-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-height: 64px;
  border-top: 1px solid var(--rule);
}

/* 证据与正文只做 opacity 淡入：错峰 60ms 仅为展示效果，不代表后端逐项完成时间 */
@media (prefers-reduced-motion: no-preference) {
  .evidence-list.is-settling .evidence-row:not(.is-missing) {
    animation: settle-fade var(--motion-fast) var(--ease-out) both;
    animation-delay: calc(var(--row-index, 0) * 60ms);
  }

  .readable-text.is-settling {
    animation: settle-fade var(--motion-fast) var(--ease-out) both;
  }
}

@keyframes settle-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

.evidence-format {
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.evidence-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.evidence-copy strong {
  font-size: 14px;
  font-weight: 550;
}

.evidence-copy span {
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-action {
  padding: 4px 8px;
  border: 0;
  color: var(--text);
  background: transparent;
}

.text-action:hover {
  background: var(--active);
}

.evidence-action {
  min-width: 52px;
}

.missing-label {
  color: var(--muted);
  font-size: 12px;
}

.is-missing .evidence-copy strong {
  color: var(--muted);
  font-weight: 500;
}

.panel-note {
  margin: 14px 0 0;
  padding: 12px;
  border-radius: var(--radius-control);
  color: var(--muted);
  background: var(--raised);
  font-size: 13px;
  line-height: 1.55;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 16px;
}

.tag-chip,
.skeleton-chip {
  display: inline-flex;
  min-height: 26px;
  align-items: center;
  padding: 3px 9px;
  border-radius: 4px;
  color: var(--text);
  background: var(--active);
  font-size: 12px;
  white-space: nowrap;
}

.tag-loading {
  display: flex;
  gap: 6px;
  margin-top: 16px;
}

.skeleton-chip {
  width: 60px;
  background: var(--rule);
}

.tag-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.tag-select,
.full-width {
  width: 100%;
  margin-top: 16px;
}

.metadata-list {
  display: grid;
  gap: 0;
  margin: 12px 0 0;
}

.metadata-list > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}

.metadata-list dt {
  color: var(--muted);
  font-size: 12px;
}

.metadata-list dd {
  min-width: 0;
  margin: 0;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.source-url {
  color: var(--muted);
}

.job-status {
  display: grid;
  gap: 4px;
  margin-top: 16px;
  padding: 12px;
  border-radius: var(--radius-control);
  background: var(--raised);
}

.job-status strong {
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
}

.job-status span {
  color: var(--muted);
  font-size: 12px;
}

.job-status.is-active strong {
  color: var(--accent);
}

.job-status.is-success strong {
  color: var(--positive);
}

.job-status.is-error strong,
.job-error {
  color: var(--negative);
}

/* 步骤线只在状态变化时推进（scaleX，220ms）；reduced-motion 下直接落到终态，几何不变 */
.job-steps {
  margin: 10px 0 0;
  padding: 0;
  display: grid;
  gap: 8px;
  list-style: none;
}

.job-steps .job-step-label {
  color: inherit;
  font-size: inherit;
}

.job-step {
  position: relative;
  min-height: 20px;
  padding-left: 22px;
  display: flex;
  align-items: center;
  color: var(--faint);
  font-size: 12px;
}

.job-step::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 16px;
  width: 1px;
  height: 14px;
  background: var(--control);
  transform: scaleY(0);
  transform-origin: top;
}

.job-step.is-done::before {
  transform: scaleY(1);
}

.job-step:last-child::before {
  display: none;
}

.job-step-mark {
  position: absolute;
  left: 0;
  top: 4px;
  width: 13px;
  height: 13px;
  border: 1px solid var(--control);
  border-radius: 50%;
  background: var(--surface);
}

.job-step.is-done {
  color: var(--muted);
}

.job-step.is-done .job-step-mark {
  border-color: var(--positive);
  background: var(--positive);
}

.job-step.is-current {
  color: var(--text);
}

.job-step.is-current .job-step-mark {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.job-step.is-error {
  color: var(--negative);
}

.job-step.is-error .job-step-mark {
  border-color: var(--negative);
  background: var(--negative-soft);
}

@media (prefers-reduced-motion: no-preference) {
  .job-step::before {
    transition: transform var(--motion-standard) var(--ease-out);
  }
}

.job-error {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.55;
}

.inline-error {
  padding: 20px;
  border-radius: var(--radius-control);
  background: var(--raised);
}

.inline-error strong {
  font-size: 14px;
}

.inline-error p {
  margin: 6px 0 10px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.compact-error {
  margin-top: 16px;
  padding: 14px;
}

.recovery-panel {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(280px, 0.8fr);
  gap: 32px;
  margin: 40px 0 0;
  padding: 32px;
}

.recovery-label {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.recovery-panel h1 {
  margin-top: 10px;
}

.recovery-panel > p:not(.recovery-label) {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 15px;
}

.recovery-actions {
  display: flex;
  gap: 8px;
  margin-top: 24px;
}

.recovery-context {
  align-self: stretch;
  padding: 20px;
  border-radius: var(--radius-control);
  background: var(--raised);
}

.recovery-context h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.recovery-context p {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.detail-skeleton {
  display: grid;
  gap: 12px;
}

.skeleton-line,
.skeleton-panel {
  border-radius: 4px;
  background: var(--active);
}

.skeleton-line {
  height: 14px;
}

.skeleton-back {
  width: 90px;
  height: 28px;
}

.skeleton-title {
  width: min(720px, 78%);
  height: 32px;
  margin-top: 10px;
}

.skeleton-copy {
  width: min(540px, 65%);
}

.skeleton-copy-short {
  width: min(380px, 46%);
}

.skeleton-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
  gap: var(--gap);
  margin-top: 18px;
}

.skeleton-panel {
  min-height: 220px;
}

.skeleton-reading {
  height: 620px;
  background: var(--surface);
}

.skeleton-stack {
  display: grid;
  gap: var(--gap);
}

.skeleton-panel-small {
  min-height: 180px;
}

.skeleton-evidence {
  height: 52px;
  margin-top: 8px;
}

.skeleton-job {
  width: 68%;
  margin-top: 18px;
}

@media (max-width: 980px) {
  .detail-header {
    display: grid;
    gap: 20px;
  }

  .header-actions {
    justify-self: start;
  }

  .detail-layout,
  .skeleton-grid {
    grid-template-columns: 1fr;
  }

  .evidence-rail {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .evidence-panel,
  .job-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .detail-page {
    padding: 20px 16px 48px;
  }

  .back-link {
    min-height: 44px;
  }

  .detail-header {
    margin-bottom: 20px;
  }

  .recovery-panel {
    grid-template-columns: 1fr;
    gap: 20px;
    margin-top: 8px;
    padding: 24px 20px;
  }

  .header-actions {
    width: 100%;
  }

  .secondary-action,
  .primary-action {
    min-height: 44px;
    flex: 1;
  }

  .detail-layout,
  .evidence-rail {
    grid-template-columns: 1fr;
  }

  .evidence-panel,
  .job-panel {
    grid-column: auto;
  }

  .reading-panel {
    min-height: 0;
    padding: 20px 16px 24px;
  }

  .reading-heading {
    align-items: flex-start;
    margin-bottom: 20px;
  }

  .readable-text {
    font-size: 17px;
    line-height: 1.8;
  }

  .reading-empty {
    min-height: 280px;
    padding: 24px 16px;
  }

  .side-panel {
    padding: 16px;
  }

  .text-action {
    min-height: 44px;
  }

  .evidence-row {
    grid-template-columns: 38px minmax(0, 1fr) auto;
  }

  .metadata-list > div {
    grid-template-columns: 82px minmax(0, 1fr);
  }
}
</style>
