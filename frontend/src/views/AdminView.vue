<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '../components/AppIcon.vue'
import api from '../api'

const overview = ref(null)
const failedJobs = ref([])
const nextBeforeId = ref(null)
const loading = ref(false)
const loadError = ref('')
const loadingMore = ref(false)
const loadMoreError = ref('')
const retryingJobId = ref(null)

const state = reactive({
  hasLoaded: false,
})

async function loadAll() {
  loading.value = true
  loadError.value = ''
  loadMoreError.value = ''

  try {
    const [overviewResponse, jobsResponse] = await Promise.all([
      api.get('/admin/overview'),
      api.get('/admin/capture-jobs/failed', {
        params: { limit: 20 },
      }),
    ])
    overview.value = overviewResponse.data
    failedJobs.value = jobsResponse.data.items
    nextBeforeId.value = jobsResponse.data.nextBeforeId
    state.hasLoaded = true
  } catch (error) {
    loadError.value = error.message || '管理数据没有加载出来'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextBeforeId.value || loadingMore.value) return

  loadingMore.value = true
  loadMoreError.value = ''
  try {
    const response = await api.get('/admin/capture-jobs/failed', {
      params: {
        limit: 20,
        beforeId: nextBeforeId.value,
      },
    })
    failedJobs.value = [...failedJobs.value, ...response.data.items]
    nextBeforeId.value = response.data.nextBeforeId
  } catch (error) {
    loadMoreError.value = error.message || '下一页没有加载出来。'
  } finally {
    loadingMore.value = false
  }
}

async function retryJob(job) {
  try {
    await ElMessageBox.confirm(
      `重试任务 #${job.jobId}？会清理失败任务的部分证据并创建新抓取任务。`,
      '确认重试',
      {
        confirmButtonText: '确认重试',
        cancelButtonText: '取消',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    return
  }

  retryingJobId.value = job.jobId
  try {
    await api.post(`/admin/capture-jobs/${job.jobId}/retry`)
    ElMessage.success('新的抓取任务已创建')
    await loadAll()
  } catch (error) {
    ElMessage.error(error.message || '重试失败，请稍后再试')
  } finally {
    retryingJobId.value = null
  }
}

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

onMounted(loadAll)
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-heading">
    <header class="page-head">
      <div>
        <h1 id="admin-heading">运营管理</h1>
        <p class="page-copy">
          查看跨账号运行状态和失败任务。此处不展示收藏正文、完整 URL 或资产内容。
        </p>
      </div>
      <button
        class="refresh-button"
        type="button"
        :disabled="loading"
        @click="loadAll"
      >
        <AppIcon name="refresh" :size="16" />
        {{ loading ? '刷新中' : '刷新' }}
      </button>
    </header>

    <div v-if="loading && !state.hasLoaded" class="loading-panel" role="status">
      正在读取运营数据…
    </div>

    <div v-else-if="loadError" class="error-panel" role="alert">
      <AppIcon name="alert" :size="20" />
      <div>
        <strong>管理数据没有加载出来</strong>
        <p>{{ loadError }}</p>
      </div>
      <button type="button" @click="loadAll">重新加载</button>
    </div>

    <template v-else>
      <section class="metrics-panel" aria-label="运行概览">
        <article data-testid="admin-total-users">
          <span>账号</span>
          <strong class="num">{{ overview.totalUsers }}</strong>
          <small>全部账号</small>
        </article>
        <article data-testid="admin-total-links">
          <span>收藏</span>
          <strong class="num">{{ overview.totalLinks }}</strong>
          <small>全部链接</small>
        </article>
        <article data-testid="admin-total-assets">
          <span>证据</span>
          <strong class="num">{{ overview.totalAssets }}</strong>
          <small>已登记资产</small>
        </article>
        <article class="jobs-cell">
          <span>抓取任务</span>
          <div class="job-counts num">
            <b>{{ overview.pendingJobs }}</b>
            <small>待处理</small>
            <b>{{ overview.runningJobs }}</b>
            <small>运行中</small>
            <b>{{ overview.succeededJobs }}</b>
            <small>成功</small>
            <b class="failed-count">{{ overview.failedJobs }}</b>
            <small>失败</small>
          </div>
        </article>
      </section>

      <section class="failed-panel" aria-labelledby="failed-heading">
        <header class="section-head">
          <div>
            <h2 id="failed-heading">待处理失败任务</h2>
            <p>每一条链接只显示最新失败任务。</p>
          </div>
          <span class="scope-note">仅域名级元数据</span>
        </header>

        <div v-if="failedJobs.length === 0" class="empty-panel">
          <strong>当前没有需要处理的失败任务</strong>
          <p>新任务失败后会出现在这里。</p>
        </div>

        <ol v-else class="job-list">
          <li v-for="job in failedJobs" :key="job.jobId" class="job-row">
            <div class="job-identity">
              <strong>{{ job.sourceHost || '未知来源' }}</strong>
              <span>任务 #{{ job.jobId }} · 链接 #{{ job.linkId }}</span>
            </div>
            <dl>
              <div>
                <dt>账号</dt>
                <dd>{{ job.ownerUsername }}</dd>
              </div>
              <div>
                <dt>尝试</dt>
                <dd class="num">{{ job.attemptCount }}/{{ job.maxAttempts }}</dd>
              </div>
              <div>
                <dt>错误码</dt>
                <dd><code>{{ job.errorCode || 'UNKNOWN' }}</code></dd>
              </div>
              <div>
                <dt>结束时间</dt>
                <dd>{{ formatDate(job.finishedAt) }}</dd>
              </div>
            </dl>
            <button
              class="retry-button"
              type="button"
              :disabled="retryingJobId === job.jobId"
              @click="retryJob(job)"
            >
              {{ retryingJobId === job.jobId ? '重试中' : '重试' }}
            </button>
          </li>
        </ol>

        <div v-if="loadMoreError" class="inline-error" role="alert">
          {{ loadMoreError }}
        </div>
        <button
          v-if="nextBeforeId"
          class="load-more-button"
          type="button"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中' : '加载更多' }}
        </button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.admin-page {
  width: min(var(--content-width), calc(100% - (2 * var(--page-pad))));
  margin: 0 auto;
  padding: 48px 0 96px;
}

.page-head,
.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.page-head {
  padding: 0 0 32px;
}

h1,
h2,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 8px;
  font-size: 32px;
  font-weight: 500;
  letter-spacing: -0.03em;
  line-height: 1.15;
}

.page-copy,
.section-head p {
  margin-bottom: 0;
  color: var(--muted);
  line-height: 1.65;
}

.refresh-button,
.retry-button,
.load-more-button,
.error-panel button {
  min-height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--control);
  border-radius: var(--radius-pill);
  padding: 0 14px;
  color: var(--text);
  background: var(--surface);
}

.refresh-button:disabled,
.retry-button:disabled,
.load-more-button:disabled {
  cursor: wait;
  opacity: 0.55;
}

.metrics-panel {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 0.7fr)) minmax(320px, 1.7fr);
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.metrics-panel article {
  min-width: 0;
  padding: 22px 20px;
}

.metrics-panel article + article {
  border-left: 1px solid var(--rule);
}

.metrics-panel article > span,
.metrics-panel article > small {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.metrics-panel article > strong {
  display: block;
  margin: 9px 0 5px;
  font-size: 30px;
  font-weight: 550;
  letter-spacing: -0.035em;
  line-height: 1;
}

.job-counts {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(4, auto 1fr);
  align-items: baseline;
  gap: 4px 10px;
}

.job-counts b {
  font-size: 20px;
  font-weight: 550;
}

.job-counts small {
  color: var(--muted);
  font-size: 11px;
}

.failed-count {
  color: var(--negative);
}

.failed-panel {
  margin-top: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.section-head {
  padding: 20px;
  border-bottom: 1px solid var(--border);
}

.section-head h2 {
  margin-bottom: 5px;
  font-size: var(--font-size-section);
}

.section-head p {
  font-size: 13px;
}

.scope-note {
  color: var(--muted);
  font-size: 12px;
}

.job-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.job-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.15fr) minmax(420px, 2fr) auto;
  align-items: center;
  gap: 20px;
  padding: 18px 20px;
}

.job-row + .job-row {
  border-top: 1px solid var(--rule);
}

.job-identity {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.job-identity strong {
  overflow: hidden;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.job-identity span {
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.job-row dl {
  margin: 0;
  display: grid;
  grid-template-columns: 0.8fr 0.55fr 1.15fr 1fr;
  gap: 16px;
}

.job-row dl div {
  min-width: 0;
}

.job-row dt {
  margin-bottom: 4px;
  color: var(--muted);
  font-size: 11px;
}

.job-row dd {
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

code {
  color: var(--negative);
  font-family: var(--font-ui);
  font-size: 12px;
}

.retry-button {
  min-width: 72px;
  border-color: var(--text);
  color: var(--on-primary);
  background: var(--text);
}

.empty-panel,
.loading-panel,
.error-panel {
  padding: 52px 24px;
  text-align: center;
}

.empty-panel p {
  margin: 8px 0 0;
  color: var(--muted);
}

.loading-panel {
  color: var(--muted);
}

.error-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--negative);
  text-align: left;
}

.error-panel p {
  margin: 4px 0 0;
  color: var(--muted);
}

.inline-error {
  margin: 0 20px;
  border-left: 3px solid var(--negative);
  padding: 10px 12px;
  color: var(--negative);
  background: var(--negative-soft);
  font-size: 13px;
}

.load-more-button {
  margin: 16px auto 20px;
  display: flex;
}

@media (max-width: 1080px) {
  .metrics-panel {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .jobs-cell {
    grid-column: 1 / -1;
    border-top: 1px solid var(--rule);
    border-left: 0 !important;
  }

  .job-row {
    grid-template-columns: minmax(170px, 1fr) minmax(380px, 2fr) auto;
  }
}

@media (max-width: 760px) {
  .admin-page {
    padding-top: 30px;
  }

  .page-head {
    display: grid;
  }

  .refresh-button {
    justify-self: start;
    min-height: 44px;
  }

  .metrics-panel {
    grid-template-columns: 1fr;
  }

  .metrics-panel article + article {
    border-top: 1px solid var(--rule);
    border-left: 0;
  }

  .job-counts {
    grid-template-columns: repeat(4, auto 1fr);
  }

  .job-row {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
  }

  .job-row dl {
    grid-column: 1 / -1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-row: 2;
  }

  .retry-button {
    min-width: 64px;
    min-height: 44px;
    grid-column: 2;
    grid-row: 1;
  }

  .load-more-button {
    min-height: 44px;
  }

  .section-head {
    display: grid;
  }
}

@media (max-width: 430px) {
  .metrics-panel article {
    padding: 18px 16px;
  }

  .job-row {
    padding: 16px;
  }

  .job-row dl {
    gap: 12px;
  }

  .error-panel {
    display: grid;
    justify-items: start;
  }
}
</style>
