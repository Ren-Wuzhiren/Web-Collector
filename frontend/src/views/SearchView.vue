<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'

const PAGE_SIZE = 20
const route = useRoute()
const router = useRouter()
const queryInput = ref(null)

const form = reactive({
  query: typeof route.query.q === 'string' ? route.query.q.slice(0, 200) : '',
  collectionId: null,
  tagIds: [],
  dateRange: [],
})

const collections = ref([])
const tags = ref([])
const catalogLoading = ref(false)
const catalogError = ref('')

const items = ref([])
const nextCursor = ref(null)
const hasMore = ref(false)
const hasSearched = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const searchError = ref('')
const loadMoreError = ref('')
const activeSearch = ref(null)
const filtersExpanded = ref(false)
let requestSequence = 0

const collectionNames = computed(
  () => new Map(collections.value.map((collection) => [collection.id, collection.name])),
)

const tagNames = computed(() => new Map(tags.value.map((tag) => [tag.id, tag.name])))

const criteriaCount = computed(() => {
  let count = form.query.trim() ? 1 : 0
  if (form.collectionId) count += 1
  if (form.tagIds.length) count += 1
  if (form.dateRange.length === 2) count += 1
  return count
})

const canSearch = computed(() => criteriaCount.value > 0)

const formDirty = computed(() => {
  if (!activeSearch.value) return false
  return filtersKey(snapshotFilters()) !== filtersKey(activeSearch.value)
})

const activeFilters = computed(() => {
  if (!activeSearch.value) return []

  const applied = []
  if (activeSearch.value.query) {
    applied.push({ key: 'query', label: `关键词：${activeSearch.value.query}` })
  }
  if (activeSearch.value.collectionId) {
    applied.push({
      key: 'collection',
      label: `集合：${collectionNames.value.get(activeSearch.value.collectionId) || '当前集合'}`,
    })
  }
  for (const tagId of activeSearch.value.tagIds) {
    applied.push({
      key: `tag-${tagId}`,
      label: `标签：${tagNames.value.get(tagId) || `#${tagId}`}`,
    })
  }
  if (activeSearch.value.dateRange.length === 2) {
    applied.push({
      key: 'date',
      label: `${activeSearch.value.dateRange[0]} 至 ${activeSearch.value.dateRange[1]}`,
    })
  }
  return applied
})

const resultSummary = computed(() => {
  if (loading.value) return '正在检索当前账号的收藏'
  if (searchError.value) return '检索没有完成'
  if (!hasSearched.value) return '输入关键词，或使用筛选条件缩小范围'
  if (!items.value.length) return '没有匹配的收藏'
  if (hasMore.value) return `已加载 ${items.value.length} 条，还有更多结果`
  return `找到 ${items.value.length} 条收藏`
})

onMounted(() => {
  if (form.query.trim()) {
    submitSearch()
  } else {
    queryInput.value?.focus()
  }
  loadCatalog()
})

async function loadCatalog() {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    const [collectionResponse, tagResponse] = await Promise.all([
      api.get('/collections'),
      api.get('/tags'),
    ])
    collections.value = collectionResponse.data
    tags.value = tagResponse.data
  } catch (error) {
    catalogError.value = error.message || '集合和标签没有加载出来。'
  } finally {
    catalogLoading.value = false
  }
}

function snapshotFilters() {
  return {
    query: form.query.trim(),
    collectionId: form.collectionId || null,
    tagIds: [...form.tagIds],
    dateRange: [...form.dateRange],
  }
}

function filtersKey(filters) {
  return JSON.stringify({
    query: filters.query,
    collectionId: filters.collectionId,
    tagIds: [...filters.tagIds].sort((left, right) => left - right),
    dateRange: filters.dateRange,
  })
}

function buildSearchParams(filters, cursor = null) {
  const params = new URLSearchParams()
  if (filters.query) params.set('q', filters.query)
  if (filters.collectionId) params.set('collectionId', filters.collectionId)
  for (const tagId of filters.tagIds) params.append('tagId', tagId)

  if (filters.dateRange.length === 2) {
    params.set('from', chinaDayStart(filters.dateRange[0]))
    params.set('to', chinaDayStart(filters.dateRange[1], 1))
  }

  if (cursor) params.set('cursor', cursor)
  params.set('limit', PAGE_SIZE)
  return params
}

function chinaDayStart(date, extraDays = 0) {
  const [year, month, day] = date.split('-').map(Number)
  const utcMillis = Date.UTC(year, month - 1, day + extraDays) - 8 * 60 * 60 * 1000
  return new Date(utcMillis).toISOString()
}

function submitSearch() {
  if (!canSearch.value || loading.value) return
  filtersExpanded.value = false
  runSearch(true)
}

async function runSearch(reset = true) {
  if (reset) {
    loading.value = true
    searchError.value = ''
    loadMoreError.value = ''
    hasSearched.value = true
    items.value = []
    nextCursor.value = null
    hasMore.value = false
    activeSearch.value = snapshotFilters()
  } else {
    if (loadingMore.value || !hasMore.value || !nextCursor.value) return
    loadingMore.value = true
    loadMoreError.value = ''
  }

  const sequence = ++requestSequence
  const filters = activeSearch.value

  try {
    const response = await api.get('/search', {
      params: buildSearchParams(filters, reset ? null : nextCursor.value),
    })
    if (sequence !== requestSequence) return

    const page = response.data
    if (reset) {
      items.value = page.items
    } else {
      const existingIds = new Set(items.value.map((item) => item.id))
      items.value.push(...page.items.filter((item) => !existingIds.has(item.id)))
    }
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    if (sequence !== requestSequence) return
    if (reset) {
      searchError.value = error.message || '搜索请求失败，请稍后重试。'
    } else {
      loadMoreError.value = '下一页没有加载出来。当前结果仍然保留。'
    }
  } finally {
    if (sequence === requestSequence) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function retrySearch() {
  if (!activeSearch.value) {
    submitSearch()
    return
  }
  loading.value = true
  searchError.value = ''
  items.value = []
  nextCursor.value = null
  hasMore.value = false
  runSearch(true)
}

function loadMore() {
  runSearch(false)
}

function clearFilters() {
  form.query = ''
  form.collectionId = null
  form.tagIds = []
  form.dateRange = []
  activeSearch.value = null
  items.value = []
  nextCursor.value = null
  hasMore.value = false
  hasSearched.value = false
  searchError.value = ''
  loadMoreError.value = ''
  filtersExpanded.value = false
  requestSequence += 1
  queryInput.value?.focus()
}

function openResult(item) {
  router.push(`/links/${item.id}`)
}

function domainOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

function formatSavedAt(value) {
  if (!value) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="search-page" aria-labelledby="search-title">
    <header class="page-heading">
      <div>
        <h1 id="search-title" class="editorial-title">搜索</h1>
        <p>从已保存的标题、描述和正文里找回网页证据。</p>
      </div>
      <p class="scope-note">检索范围：当前账号的全部收藏</p>
    </header>

    <div class="search-workspace">
    <section class="search-workbench" aria-labelledby="search-form-title">
      <h2 id="search-form-title" class="visually-hidden">搜索条件</h2>

      <form @submit.prevent="submitSearch">
        <label class="field-label" for="search-query">关键词</label>
        <div class="query-row">
          <el-input
            id="search-query"
            ref="queryInput"
            v-model="form.query"
            size="large"
            maxlength="200"
            clearable
            placeholder="搜索标题、描述和正文"
            aria-describedby="search-query-help"
          >
            <template #prefix>
              <svg
                class="search-icon"
                viewBox="0 0 20 20"
                fill="none"
                aria-hidden="true"
              >
                <circle cx="8.5" cy="8.5" r="5.5" stroke="currentColor" stroke-width="1.5" />
                <path
                  d="M12.75 12.75L17 17"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
              </svg>
            </template>
          </el-input>
          <el-button
            class="search-button"
            type="primary"
            native-type="submit"
            size="large"
            :loading="loading"
            :disabled="!canSearch || loading"
          >
            搜索档案
          </el-button>
        </div>
        <p id="search-query-help" class="field-help">
          关键词最多 200 个字符。集合、标签和保存日期可以组合使用。
        </p>

        <button
          class="mobile-filter-toggle"
          type="button"
          aria-controls="search-filter-group"
          :aria-expanded="filtersExpanded"
          @click="filtersExpanded = !filtersExpanded"
        >
          <span>筛选条件</span>
          <span class="filter-toggle-meta">
            {{ criteriaCount ? `${criteriaCount} 组已选` : '可选' }}
            <span aria-hidden="true" :class="{ 'is-open': filtersExpanded }">⌄</span>
          </span>
        </button>

        <fieldset
          id="search-filter-group"
          class="filter-group"
          :class="{ 'is-open': filtersExpanded }"
        >
          <legend class="visually-hidden">筛选条件</legend>
          <div class="filter-grid">
            <label class="filter-field">
              <span class="field-label">集合</span>
              <el-select
                v-model="form.collectionId"
                clearable
                filterable
                :loading="catalogLoading"
                placeholder="全部集合"
                aria-label="按集合筛选"
              >
                <el-option
                  v-for="collection in collections"
                  :key="collection.id"
                  :label="collection.name"
                  :value="collection.id"
                />
              </el-select>
            </label>

            <label class="filter-field">
              <span class="field-label">标签</span>
              <el-select
                v-model="form.tagIds"
                multiple
                collapse-tags
                collapse-tags-tooltip
                clearable
                filterable
                :loading="catalogLoading"
                placeholder="全部标签"
                aria-label="按标签筛选"
              >
                <el-option
                  v-for="tag in tags"
                  :key="tag.id"
                  :label="tag.name"
                  :value="tag.id"
                />
              </el-select>
            </label>

            <label class="filter-field filter-field-wide">
              <span class="field-label">保存日期</span>
              <el-date-picker
                v-model="form.dateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                unlink-panels
                aria-label="按保存日期筛选"
              />
            </label>
          </div>

          <div class="filter-footer">
            <p>
              <span v-if="criteriaCount">{{ criteriaCount }} 组条件</span>
              <span v-else>选择至少一个条件后开始检索</span>
            </p>
            <button
              class="text-action"
              type="button"
              :disabled="criteriaCount === 0 && !hasSearched"
              @click="clearFilters"
            >
              清除全部条件
            </button>
          </div>
        </fieldset>
      </form>

      <div v-if="catalogError" class="inline-error" role="alert">
        <p>集合和标签没有加载出来。关键词搜索仍可使用。</p>
        <button class="text-action" type="button" @click="loadCatalog">重新加载</button>
      </div>
    </section>

    <section class="results-panel" aria-labelledby="results-title">
      <header class="results-heading">
        <div>
          <h2 id="results-title">检索结果</h2>
          <p aria-live="polite">{{ resultSummary }}</p>
        </div>

        <ul v-if="activeFilters.length" class="active-filters" aria-label="当前生效的搜索条件">
          <li v-for="filter in activeFilters" :key="filter.key">{{ filter.label }}</li>
        </ul>
      </header>

      <p v-if="formDirty" class="dirty-notice">
        条件已修改。点击“搜索档案”后更新结果，当前列表仍使用上一次条件。
      </p>

      <div v-if="loading" class="loading-state" role="status" aria-label="正在搜索">
        <div v-for="index in 4" :key="index" class="skeleton-row" aria-hidden="true">
          <span class="skeleton-line skeleton-meta"></span>
          <span class="skeleton-line skeleton-title"></span>
          <span class="skeleton-line skeleton-description"></span>
        </div>
      </div>

      <div v-else-if="searchError" class="result-state error-state" role="alert">
        <h3>搜索没有完成</h3>
        <p>{{ searchError }}</p>
        <button class="state-action secondary-action" type="button" @click="retrySearch">
          重试搜索
        </button>
      </div>

      <div v-else-if="!hasSearched" class="result-state initial-state">
        <span class="state-mark" aria-hidden="true">
          <svg viewBox="0 0 28 28" fill="none">
            <path d="M5 3.75H18.5L23 8.25V24.25H5V3.75Z" stroke="currentColor" />
            <path d="M18.5 3.75V8.25H23M8.5 13H19.5M8.5 17H17.5" stroke="currentColor" />
          </svg>
        </span>
        <div>
          <h3>输入关键词，或使用筛选条件缩小范围</h3>
          <p>可以从标题、描述和保存时提取的正文中检索，不必回到原网页查找。</p>
        </div>
      </div>

      <div v-else-if="!items.length" class="result-state empty-state">
        <span class="state-mark" aria-hidden="true">
          <svg viewBox="0 0 28 28" fill="none">
            <circle cx="12" cy="12" r="7.5" stroke="currentColor" />
            <path d="M17.5 17.5L24 24" stroke="currentColor" stroke-linecap="round" />
          </svg>
        </span>
        <div>
          <h3>没有匹配的收藏</h3>
          <p>试试更短的关键词，或清除日期和标签筛选。</p>
          <button class="text-action" type="button" @click="clearFilters">
            清除全部条件
          </button>
        </div>
      </div>

      <template v-else>
        <ol class="results-list">
          <li v-for="item in items" :key="item.id" class="result-row">
            <div class="result-main">
              <p class="result-meta-line">
                <span>{{ domainOf(item.originalUrl) }}</span>
                <span aria-hidden="true">·</span>
                <span>保存于 {{ formatSavedAt(item.createdAt) }}</span>
              </p>
              <a class="result-title" :href="`/links/${item.id}`" @click.prevent="openResult(item)">
                {{ item.title || item.originalUrl }}
              </a>
              <p class="result-description">
                {{ item.description || '这条收藏还没有描述。' }}
              </p>
            </div>

            <div class="result-actions">
              <span class="collection-name">
                {{ collectionNames.get(item.collectionId) || '未归类' }}
              </span>
              <a
                class="original-link"
                :href="item.originalUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                查看原文
                <span aria-hidden="true">↗</span>
              </a>
              <button
                class="row-open"
                type="button"
                :aria-label="`打开收藏：${item.title || item.originalUrl}`"
                @click="openResult(item)"
              >
                <span>打开档案</span>
                <span aria-hidden="true">→</span>
              </button>
            </div>
          </li>
        </ol>

        <div v-if="loadMoreError" class="load-more-error" role="alert">
          <p>{{ loadMoreError }}</p>
          <button class="text-action" type="button" @click="loadMore">重试下一页</button>
        </div>

        <div class="results-footer">
          <el-button
            v-if="hasMore"
            class="load-more-button"
            :loading="loadingMore"
            @click="loadMore"
          >
            加载更多
          </el-button>
          <p v-else>已显示本次检索的全部结果</p>
        </div>
      </template>
    </section>
    </div>
  </section>
</template>

<style scoped>
.search-page {
  width: min(var(--content-width), calc(100% - (2 * var(--page-pad))));
  margin: 0 auto;
  padding: 56px 0 96px;
  color: var(--text);
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 40px;
}

h1,
h2,
h3,
p {
  margin: 0;
}

.page-heading h1 {
  font-size: var(--font-size-editorial);
  font-weight: 500;
  letter-spacing: -0.035em;
  line-height: var(--line-height-display);
}

.page-heading p {
  max-width: 44em;
  margin-top: 18px;
  color: var(--muted);
  font-size: var(--font-size-body);
  line-height: var(--line-height-body);
}

.page-heading .scope-note {
  margin: 0;
  white-space: nowrap;
  font-size: var(--font-size-small);
}

.search-workspace {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  align-items: start;
  gap: 32px;
}

.search-workbench {
  position: sticky;
  top: calc(var(--topbar-height) + 24px);
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.field-label {
  display: block;
  margin-bottom: 8px;
  color: var(--text);
  font-size: 13px;
  font-weight: 550;
  line-height: 1.4;
}

.query-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}

.search-icon {
  width: 18px;
  height: 18px;
  color: var(--muted);
}

.search-workbench :deep(.el-input__wrapper),
.search-workbench :deep(.el-select__wrapper) {
  min-height: 42px;
  padding: 1px 12px;
  border: 1px solid var(--control);
  border-radius: var(--radius-control);
  background: var(--surface);
  box-shadow: none; /* taste-skill-ignore: Element Plus draws its field edge with box-shadow; the border replaces it. */
  transition:
    border-color 140ms ease-out,
    background-color 140ms ease-out;
}

.search-workbench :deep(.el-input__wrapper:hover),
.search-workbench :deep(.el-select__wrapper:hover) {
  border-color: var(--control-hover);
}

.search-workbench :deep(.el-input__wrapper.is-focus),
.search-workbench :deep(.el-select__wrapper.is-focused) {
  border-color: var(--accent);
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

.search-workbench :deep(.el-input__inner),
.search-workbench :deep(.el-select__selected-item),
.search-workbench :deep(.el-range-input) {
  color: var(--text);
  font-size: 14px;
}

.search-workbench :deep(.el-input__inner::placeholder),
.search-workbench :deep(.el-range-input::placeholder),
.search-workbench :deep(.el-select__placeholder) {
  color: var(--muted);
}

.search-workbench :deep(.el-date-editor.el-input__wrapper) {
  width: 100%;
}

.search-button {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  border: 1px solid var(--text);
  border-radius: var(--radius-pill);
  color: var(--on-primary);
  background: var(--text);
  font-weight: 500;
}

.search-button:not(.is-disabled):hover,
.search-button:not(.is-disabled):focus {
  border-color: var(--text);
  background: var(--text);
}

.search-button.is-disabled {
  border-color: var(--control);
  background: var(--active);
  color: var(--faint);
  opacity: 1;
}

.field-help {
  margin-top: 8px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

.mobile-filter-toggle {
  display: none;
}

.filter-group {
  min-width: 0;
  margin: 22px 0 0;
  border: 0;
  border-top: 1px solid var(--rule);
  padding: 20px 0 0;
}

.filter-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}

.filter-field {
  display: block;
  min-width: 0;
}

.filter-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
  color: var(--muted);
  font-size: 13px;
}

.text-action {
  min-height: 32px;
  border: 0;
  padding: 0;
  color: var(--text);
  background: transparent;
  font-size: 13px;
  font-weight: 550;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.text-action:disabled {
  color: var(--muted);
  cursor: default;
  text-decoration: none;
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 20px;
  border: 1px solid var(--negative-border);
  border-radius: var(--radius-control);
  padding: 10px 12px;
  color: var(--negative);
  background: var(--negative-soft);
  font-size: 13px;
  line-height: 1.5;
}

.results-panel {
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
}

.results-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--border);
  padding: 0 0 18px;
}

.results-heading h2 {
  font-size: 18px;
  font-weight: 500;
  line-height: 1.4;
}

.results-heading > div > p {
  margin-top: 4px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

.active-filters {
  display: flex;
  max-width: 58%;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.active-filters li {
  min-height: 24px;
  max-width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  padding: 3px 8px;
  overflow: hidden;
  color: var(--muted);
  background: var(--raised);
  font-size: 12px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dirty-notice {
  margin-top: 14px;
  border-left: 3px solid var(--accent);
  padding: 7px 10px;
  color: var(--muted);
  background: var(--raised);
  font-size: 13px;
  line-height: 1.5;
}

.loading-state {
  padding: 6px 0;
}

.skeleton-row {
  display: grid;
  gap: 9px;
  border-bottom: 1px solid var(--rule);
  padding: 20px 0;
}

.skeleton-row:last-child {
  border-bottom: 0;
}

.skeleton-line {
  display: block;
  height: 11px;
  border-radius: 3px;
  background: var(--active);
}

.skeleton-meta {
  width: 190px;
}

.skeleton-title {
  width: min(520px, 66%);
  height: 18px;
}

.skeleton-description {
  width: min(720px, 82%);
}

.result-state {
  display: flex;
  min-height: 240px;
  align-items: flex-start;
  gap: 16px;
  padding: 42px 24px;
  border: 1px dashed var(--control);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.result-state h3 {
  font-size: 18px;
  font-weight: 500;
  line-height: 1.45;
}

.result-state p {
  max-width: 620px;
  margin-top: 8px;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}

.result-state .text-action {
  margin-top: 12px;
}

.state-mark {
  display: grid;
  width: 40px;
  height: 40px;
  flex: none;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 50%;
  color: var(--muted);
  background: var(--raised);
}

.state-mark svg {
  width: 24px;
  height: 24px;
}

.error-state {
  color: var(--negative);
}

.state-action {
  min-height: 36px;
  margin-top: 16px;
  border: 1px solid var(--control);
  border-radius: var(--radius-control);
  padding: 0 14px;
  color: var(--text);
  background: var(--surface);
  font-size: 13px;
  font-weight: 550;
}

.results-list {
  margin: 24px 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
  list-style: none;
}

.result-row {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  padding: 20px;
  background: var(--surface);
  transition: border-color var(--motion-fast) var(--ease-out);
}

.result-row:hover,
.result-row:focus-within {
  border-color: var(--control-hover);
}

.result-main {
  min-width: 0;
}

.result-meta-line {
  display: flex;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
  white-space: nowrap;
}

.result-meta-line span:first-child {
  overflow: hidden;
  color: var(--accent);
  text-overflow: ellipsis;
}

.result-title {
  display: block;
  width: fit-content;
  max-width: 100%;
  margin-top: 7px;
  overflow: hidden;
  color: var(--text);
  font-size: 17px;
  font-weight: 550;
  letter-spacing: -0.015em;
  line-height: 1.45;
  text-decoration: none;
  text-overflow: ellipsis;
  display: -webkit-box;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.result-title:hover,
.result-title:focus-visible {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 4px;
}

.result-description {
  display: -webkit-box;
  max-width: 76ch;
  margin-top: 7px;
  overflow: hidden;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.result-actions {
  display: flex;
  min-width: 0;
  margin-top: auto;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
}

.collection-name {
  margin-right: auto;
  max-width: 100%;
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.original-link {
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
}

.original-link:hover,
.original-link:focus-visible {
  color: var(--text);
  text-decoration: underline;
}

.row-open {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
  border: 0;
  padding: 0;
  color: var(--text);
  background: transparent;
  font-size: 13px;
  font-weight: 550;
}

.row-open span:last-child {
  transition: transform var(--motion-fast) var(--ease-out);
}

.row-open:hover span:last-child,
.row-open:focus-visible span:last-child {
  transform: translateX(3px);
}

.load-more-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  border: 1px solid var(--negative-border);
  border-radius: var(--radius-control);
  padding: 10px 12px;
  color: var(--negative);
  background: var(--negative-soft);
  font-size: 13px;
}

.results-footer {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.results-footer p {
  color: var(--muted);
  font-size: 13px;
}

.load-more-button {
  min-width: 132px;
  min-height: 36px;
  border-color: var(--control);
  color: var(--text);
  background: var(--surface);
  font-weight: 500;
  border-radius: var(--radius-pill);
}

.load-more-button:hover,
.load-more-button:focus {
  border-color: var(--text);
  color: var(--text);
  background: var(--surface);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 900px) {
  .search-page {
    padding-top: 38px;
  }

  .search-workspace {
    display: block;
  }

  .search-workbench {
    position: static;
  }

  .results-panel {
    margin-top: 24px;
  }

  .filter-grid {
    grid-template-columns: 1fr 1fr;
  }

  .mobile-filter-toggle {
    display: flex;
    width: 100%;
    min-height: 44px;
    align-items: center;
    justify-content: space-between;
    margin-top: 16px;
    border: 1px solid var(--control);
    border-radius: var(--radius-control);
    padding: 0 12px;
    color: var(--text);
    background: var(--surface);
    font-size: 13px;
    font-weight: 550;
  }

  .filter-toggle-meta {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 13px;
    font-weight: 400;
  }

  .filter-toggle-meta > span {
    display: inline-block;
    font-size: 16px;
    line-height: 1;
    transition: transform var(--motion-fast) var(--ease-out);
  }

  .filter-toggle-meta > span.is-open {
    transform: rotate(180deg);
  }

  .filter-group {
    display: none;
  }

  .filter-group.is-open {
    display: block;
  }

  .filter-field-wide {
    grid-column: 1 / -1;
  }

}

@media (max-width: 640px) {
  .search-page {
    padding: 30px 0 56px;
  }

  .page-heading {
    display: block;
    margin-bottom: 24px;
  }

  .page-heading .scope-note {
    margin-top: 8px;
    white-space: normal;
  }

  .search-workbench {
    padding: 18px;
  }

  .query-row {
    grid-template-columns: 1fr;
  }

  .search-button {
    width: 100%;
    min-height: 44px;
  }

  .filter-grid {
    grid-template-columns: 1fr;
  }

  .filter-field-wide {
    grid-column: auto;
  }

  .filter-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .inline-error,
  .load-more-error {
    align-items: flex-start;
    flex-direction: column;
  }

  .results-panel {
    padding: 0;
  }

  .results-heading {
    display: block;
  }

  .active-filters {
    max-width: none;
    justify-content: flex-start;
    margin-top: 12px;
  }

  .result-state {
    min-height: 220px;
    padding: 28px 20px;
  }

  .state-mark {
    display: none;
  }

  .results-list {
    grid-template-columns: 1fr;
  }

  .result-row {
    gap: 16px;
    padding: 20px;
  }

  .result-actions {
    align-items: flex-start;
    flex-direction: row;
    flex-wrap: wrap;
    gap: 8px 16px;
  }

  .collection-name {
    width: 100%;
  }

  .row-open {
    min-height: 44px;
    margin-left: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
  .search-workbench :deep(.el-input__wrapper),
  .search-workbench :deep(.el-select__wrapper),
  .row-open span:last-child,
  .filter-toggle-meta > span {
    transition: none;
  }
}
</style>
