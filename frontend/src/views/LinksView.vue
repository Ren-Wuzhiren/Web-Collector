<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import AppIcon from '../components/AppIcon.vue'
import ArchiveCounter from '../components/ArchiveCounter.vue'
import SaveLinkDrawer from '../components/SaveLinkDrawer.vue'
import { formatClock, formatDomain, formatRelativeTime } from '../utils/format'
import { navigateWithTransition, shouldUseViewTransition } from '../utils/viewTransition'

const router = useRouter()
const links = ref([])
const collections = ref([])
const tags = ref([])
const loading = ref(false)
const refreshing = ref(false)
const loadError = ref('')
const collectionsAvailable = ref(false)
const tagsAvailable = ref(false)
const lastLoadedAt = ref(null)
const activeCollectionId = ref('all')
const saveDrawerOpen = ref(false)

const collectionNames = computed(
  () => new Map(collections.value.map((collection) => [collection.id, collection.name])),
)

const filteredLinks = computed(() => {
  if (activeCollectionId.value === 'all') return links.value
  return links.value.filter(
    (link) => link.collectionId === activeCollectionId.value,
  )
})

const hasCatalogError = computed(
  () => !collectionsAvailable.value || !tagsAvailable.value,
)

const activeFilterLabel = computed(() => {
  if (activeCollectionId.value === 'all') return '全部收藏'
  return collectionNames.value.get(activeCollectionId.value) || '当前集合'
})

onMounted(() => {
  loadArchive()
})

async function loadArchive(options = {}) {
  const quiet = options.quiet === true
  if (loading.value || refreshing.value) return

  if (quiet) {
    refreshing.value = true
  } else {
    loading.value = true
  }
  loadError.value = ''

  const [linkResult, collectionResult, tagResult] = await Promise.allSettled([
    api.get('/links'),
    api.get('/collections'),
    api.get('/tags'),
  ])

  if (linkResult.status === 'fulfilled') {
    links.value = linkResult.value.data
    if (
      activeCollectionId.value !== 'all' &&
      !collectionResult.value?.data?.some(
        (collection) => collection.id === activeCollectionId.value,
      )
    ) {
      activeCollectionId.value = 'all'
    }
  } else {
    loadError.value = linkResult.reason?.message || '收藏列表没有加载出来。'
  }

  if (collectionResult.status === 'fulfilled') {
    collections.value = collectionResult.value.data
    collectionsAvailable.value = true
  } else {
    collectionsAvailable.value = false
  }
  if (tagResult.status === 'fulfilled') {
    tags.value = tagResult.value.data
    tagsAvailable.value = true
  } else {
    tagsAvailable.value = false
  }

  if (linkResult.status === 'fulfilled') {
    lastLoadedAt.value = new Date()
  }

  loading.value = false
  refreshing.value = false
}

function selectCollection(collectionId) {
  activeCollectionId.value = collectionId
}

function handleSaved({ link }) {
  const existingIndex = links.value.findIndex((item) => item.id === link.id)
  if (existingIndex >= 0) {
    links.value.splice(existingIndex, 1)
  } else {
    const collection = collections.value.find(
      (item) => item.id === link.collectionId,
    )
    if (collection) {
      collection.linkCount = (collection.linkCount ?? 0) + 1
    }
  }
  links.value.unshift(link)
  lastLoadedAt.value = new Date()
}

function handleSaveDrawer(payload) {
  handleSaved(payload)
  router.push({
    path: `/links/${payload.link.id}`,
    query: { job: payload.job.id },
  })
}

function countForCollection(collection) {
  return collection.linkCount ?? 0
}

function openLink(link, navigate, event) {
  if (!shouldUseViewTransition(event)) return navigate(event)
  event.preventDefault()
  navigateWithTransition(router, `/links/${link.id}`)
}
</script>

<template>
  <div class="links-view">
    <header class="page-heading">
      <div>
        <h1>收藏</h1>
        <p>当前账号保存的网页、快照和可读正文。</p>
      </div>
      <button
        class="primary-action"
        type="button"
        data-testid="open-save-drawer"
        @click="saveDrawerOpen = true"
      >
        <AppIcon name="plus" :size="17" />
        <span>保存网页</span>
      </button>
    </header>

    <section class="metrics-strip" aria-label="收藏概况">
      <div class="metric metric-primary">
        <span>已保存</span>
        <strong><ArchiveCounter :value="loading ? null : links.length" /></strong>
      </div>
      <div class="metric">
        <span>集合</span>
        <strong><ArchiveCounter :value="loading ? null : collections.length" /></strong>
      </div>
      <div class="metric">
        <span>标签</span>
        <strong><ArchiveCounter :value="loading ? null : tags.length" /></strong>
      </div>
    </section>

    <section class="collection-filter" aria-labelledby="collection-filter-title">
      <h2 id="collection-filter-title">按集合查看</h2>
      <div class="filter-row" role="tablist" aria-label="集合筛选">
        <button
          class="filter-tab"
          :class="{ 'is-active': activeCollectionId === 'all' }"
          type="button"
          role="tab"
          :aria-selected="activeCollectionId === 'all'"
          @click="selectCollection('all')"
        >
          全部收藏
          <ArchiveCounter :value="links.length" />
        </button>
        <button
          v-for="collection in collections"
          :key="collection.id"
          class="filter-tab"
          :class="{ 'is-active': activeCollectionId === collection.id }"
          type="button"
          role="tab"
          :aria-selected="activeCollectionId === collection.id"
          @click="selectCollection(collection.id)"
        >
          {{ collection.name }}
          <ArchiveCounter :value="countForCollection(collection)" />
        </button>
      </div>
    </section>

    <section class="archive-panel" aria-labelledby="archive-list-title" :aria-busy="loading">
      <header class="panel-head">
        <div>
          <h2 id="archive-list-title">{{ activeFilterLabel }}</h2>
          <p>
            {{ refreshing ? '正在刷新收藏…' : `显示 ${filteredLinks.length} 条收藏` }}
          </p>
        </div>
        <div class="freshness">
          <span v-if="lastLoadedAt">
            更新于 <time class="num">{{ formatClock(lastLoadedAt) }}</time>
          </span>
          <button
            class="refresh-button"
            type="button"
            :disabled="loading || refreshing"
            @click="loadArchive({ quiet: true })"
          >
            <AppIcon name="refresh" :size="16" />
            <span>{{ refreshing ? '刷新中' : '刷新' }}</span>
          </button>
        </div>
      </header>

      <div v-if="loading" class="loading-list" aria-label="正在加载收藏">
        <div v-for="index in 5" :key="index" class="loading-row">
          <span class="skeleton skeleton-title"></span>
          <span class="skeleton skeleton-meta"></span>
        </div>
      </div>

      <div v-else-if="loadError" class="block-state block-error" role="alert">
        <span class="state-mark">
          <AppIcon name="alert" :size="20" />
        </span>
        <div>
          <h3>收藏列表没有加载出来</h3>
          <p>{{ loadError }}</p>
          <button class="text-action" type="button" @click="loadArchive()">重试</button>
        </div>
      </div>

      <div v-else-if="filteredLinks.length === 0" class="block-state block-empty">
        <span class="state-mark state-mark-neutral">
          <AppIcon name="archive" :size="21" />
        </span>
        <div>
          <h3 v-if="links.length === 0">还没有收藏</h3>
          <h3 v-else>这个集合里还没有收藏</h3>
          <p v-if="links.length === 0">
            保存一个 URL，正文和页面副本会在这里出现。
          </p>
          <p v-else>切换到“全部收藏”，或保存一个新网页到这个集合。</p>
          <button class="text-action" type="button" @click="saveDrawerOpen = true">
            保存网页
          </button>
        </div>
      </div>

      <ol v-else class="link-list">
        <li v-for="link in filteredLinks" :key="link.id" class="link-row">
          <router-link
            v-slot="{ href, navigate }"
            custom
            :to="`/links/${link.id}`"
          >
            <a
              class="link-identity"
              :href="href"
              :aria-label="`打开收藏 ${link.title || link.originalUrl}`"
              @click="openLink(link, navigate, $event)"
            >
              <span class="source-mark" aria-hidden="true">
                {{ formatDomain(link.originalUrl).slice(0, 1).toUpperCase() }}
              </span>
              <div class="link-copy">
                <h3 :style="{ 'view-transition-name': `link-title-${link.id}` }">
                  {{ link.title || '未命名网页' }}
                </h3>
                <p>
                  <span>{{ formatDomain(link.originalUrl) }}</span>
                  <span class="meta-divider" aria-hidden="true">·</span>
                  <span>{{ formatRelativeTime(link.updatedAt) }}</span>
                </p>
              </div>
            </a>
          </router-link>

          <div class="collection-cell">
            <span>{{ collectionNames.get(link.collectionId) || '未归入集合' }}</span>
          </div>

          <a
            class="external-action"
            :href="link.originalUrl"
            target="_blank"
            rel="noopener noreferrer"
            :aria-label="`在新窗口打开 ${link.title || link.originalUrl}`"
            title="打开原始网页"
          >
            <AppIcon name="external" :size="17" />
          </a>
        </li>
      </ol>

      <p v-if="hasCatalogError && !loading && !loadError" class="catalog-note">
        集合或标签目录暂时没有完整加载。收藏列表仍可使用，保存前请确认集合选项。
      </p>
    </section>

    <SaveLinkDrawer
      v-model:open="saveDrawerOpen"
      :collections="collections"
      @saved="handleSaveDrawer"
    />
  </div>
</template>

<style scoped>
.links-view {
  width: min(var(--content-width), calc(100% - (2 * var(--page-pad))));
  margin: 0 auto;
  padding: 32px 0 56px;
}

.page-heading {
  min-height: 64px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.page-heading h1 {
  margin: 0;
  font-size: var(--font-size-title);
  line-height: 1.25;
  font-weight: 650;
  letter-spacing: -0.025em;
}

.page-heading p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.primary-action {
  min-height: 40px;
  padding: 0 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--text);
  border-radius: var(--radius-control);
  color: var(--surface);
  background: var(--text);
  font-weight: 550;
  transition: background-color var(--motion-fast) var(--ease-out);
}

.primary-action:hover {
  background: var(--text-hover);
}

.metrics-strip {
  margin-top: 26px;
  padding: 16px 20px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.metric {
  min-width: 0;
  padding: 0 24px;
  display: grid;
  gap: 4px;
}

.metric:first-child {
  padding-left: 0;
}

.metric:not(:first-child) {
  border-left: 1px solid var(--rule);
}

.metric > span {
  color: var(--muted);
  font-size: 13px;
}

.metric strong {
  font-size: 26px;
  line-height: 1.2;
  font-weight: 500;
  letter-spacing: -0.025em;
}

.metric-primary strong {
  color: var(--accent);
}

.collection-filter {
  margin-top: 22px;
}

.collection-filter h2 {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.4;
  font-weight: 500;
}

.filter-row {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: thin;
}

.filter-tab {
  min-height: 38px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--muted);
  background: transparent;
  white-space: nowrap;
  transition:
    color var(--motion-fast) var(--ease-out),
    border-color var(--motion-fast) var(--ease-out);
}

.filter-tab:hover {
  color: var(--text);
}

.filter-tab.is-active {
  border-bottom-color: var(--text);
  color: var(--text);
}

.filter-tab span {
  color: var(--muted);
  font-size: 13px;
}

.archive-panel {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.panel-head {
  min-height: 70px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--border);
}

.panel-head h2 {
  margin: 0;
  font-size: var(--font-size-section);
  line-height: 1.4;
  font-weight: 600;
}

.panel-head p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.freshness {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--muted);
  font-size: 13px;
  white-space: nowrap;
}

.refresh-button {
  min-height: 34px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text);
  background: var(--surface);
}

.refresh-button:hover:not(:disabled) {
  background: var(--raised);
}

.refresh-button:disabled {
  color: var(--muted);
  background: var(--raised);
  cursor: wait;
}

.link-list,
.loading-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.link-row,
.loading-row {
  min-height: var(--row-height);
  padding: 12px 20px;
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(120px, 0.32fr) 40px;
  align-items: center;
  gap: 20px;
}

.link-row + .link-row {
  border-top: 1px solid var(--rule);
}

.link-row {
  transition: background-color var(--motion-fast) var(--ease-out);
}

.link-row:hover {
  background: var(--raised);
}

.link-identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  color: inherit;
  text-decoration: none;
}

.source-mark {
  width: 34px;
  height: 38px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 6px;
  color: var(--muted);
  background: var(--raised);
  font-size: 13px;
  font-weight: 600;
}

.link-copy {
  min-width: 0;
}

.link-copy h3 {
  margin: 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 1.45;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-copy p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta-divider {
  margin: 0 5px;
}

.collection-cell {
  min-width: 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.external-action {
  width: 36px;
  height: 36px;
  justify-self: end;
  display: grid;
  place-items: center;
  border-radius: var(--radius-control);
  color: var(--muted);
  text-decoration: none;
  transition:
    color var(--motion-fast) var(--ease-out),
    background-color var(--motion-fast) var(--ease-out);
}

.external-action:hover {
  color: var(--text);
  background: var(--active);
}

.loading-row {
  grid-template-columns: 1fr;
  gap: 9px;
}

.loading-row + .loading-row {
  border-top: 1px solid var(--rule);
}

.skeleton {
  display: block;
  border-radius: 4px;
  background: var(--active);
}

.skeleton-title {
  width: min(360px, 52%);
  height: 15px;
}

.skeleton-meta {
  width: min(220px, 34%);
  height: 10px;
}

.block-state {
  min-height: 218px;
  padding: 40px;
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.state-mark {
  width: 36px;
  height: 36px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--negative);
  background: var(--negative-soft);
}

.state-mark-neutral {
  color: var(--muted);
  background: var(--raised);
}

.block-state h3 {
  margin: 2px 0 0;
  font-size: 16px;
  font-weight: 600;
}

.block-state p {
  margin: 7px 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.text-action {
  min-height: 36px;
  padding: 0;
  border: 0;
  color: var(--text);
  background: transparent;
  font-weight: 550;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.catalog-note {
  margin: 0;
  padding: 12px 20px;
  border-top: 1px solid var(--border);
  color: var(--warning);
  background: var(--warning-soft);
  font-size: 13px;
}

@media (max-width: 980px) {
  .link-row {
    grid-template-columns: minmax(240px, 1fr) minmax(100px, 0.28fr) 40px;
    gap: 14px;
  }
}

@media (max-width: 720px) {
  .links-view {
    padding-top: 22px;
  }

  .page-heading {
    align-items: center;
  }

  .page-heading p {
    max-width: 230px;
  }

  .primary-action {
    min-width: 44px;
    min-height: 44px;
    padding: 0 13px;
  }

  .metrics-strip {
    padding: 14px 16px;
  }

  .metric {
    padding: 0 14px;
  }

  .metric strong {
    font-size: 22px;
  }

  .panel-head {
    padding: 14px 16px;
  }

  .freshness > span {
    display: none;
  }

  .refresh-button,
  .external-action {
    min-width: 44px;
    min-height: 44px;
  }

  .refresh-button span {
    display: none;
  }

  .link-row {
    grid-template-columns: minmax(0, 1fr) 44px;
    gap: 10px;
    padding: 14px 16px;
  }

  .collection-cell {
    grid-column: 1;
    padding-left: 46px;
    margin-top: -8px;
  }

  .external-action {
    grid-column: 2;
    grid-row: 1 / span 2;
  }

  .block-state {
    min-height: 250px;
    padding: 32px 20px;
  }
}

@media (max-width: 420px) {
  .page-heading h1 {
    font-size: 22px;
  }

  .page-heading p {
    font-size: 13px;
  }

  .primary-action span {
    display: none;
  }

  .metric {
    padding: 0 10px;
  }

  .metric strong {
    font-size: 20px;
  }
}
</style>
