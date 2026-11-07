<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import AppIcon from '../components/AppIcon.vue'
import ArchiveArtwork from '../components/ArchiveArtwork.vue'
import ArchiveCounter from '../components/ArchiveCounter.vue'
import SaveLinkDrawer from '../components/SaveLinkDrawer.vue'
import { formatClock, formatDomain, formatRelativeTime } from '../utils/format'
import { navigateWithTransition, shouldUseViewTransition } from '../utils/viewTransition'

const LINK_VIEW_STORAGE_KEY = 'webcollector-link-view'

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
const linkView = ref(readLinkView())

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

watch(linkView, (value) => {
  try {
    localStorage.setItem(LINK_VIEW_STORAGE_KEY, value)
  } catch {
    // 本地存储不可用时仍切换当前视图，只是无法跨刷新保留。
  }
})

onMounted(() => {
  loadArchive()
})

function readLinkView() {
  try {
    return localStorage.getItem(LINK_VIEW_STORAGE_KEY) === 'list'
      ? 'list'
      : 'grid'
  } catch {
    return 'grid'
  }
}

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

function setLinkView(view) {
  linkView.value = view
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
  const titleElement = event.currentTarget.querySelector('h3')
  if (!shouldUseViewTransition(event, titleElement)) return navigate(event)
  event.preventDefault()
  navigateWithTransition(router, `/links/${link.id}`, {
    transitionTitle: link.title || '未命名网页',
  })
}
</script>

<template>
  <div class="links-view">
    <header class="page-heading">
      <div class="heading-copy">
        <h1 class="editorial-title">收藏</h1>
        <p>保存网页、可读正文和页面证据，让以后仍然可以核对。</p>
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

    <div class="archive-workspace">
      <aside class="collection-rail" aria-labelledby="collection-filter-title">
        <div class="rail-heading">
          <h2 id="collection-filter-title">按集合查看</h2>
          <p>{{ activeFilterLabel }}</p>
        </div>
        <div class="collection-list" role="tablist" aria-label="集合筛选">
          <button
            class="collection-tab"
            :class="{ 'is-active': activeCollectionId === 'all' }"
            type="button"
            role="tab"
            :aria-selected="activeCollectionId === 'all'"
            @click="selectCollection('all')"
          >
            <span>全部收藏</span>
            <ArchiveCounter :value="links.length" />
          </button>
          <button
            v-for="collection in collections"
            :key="collection.id"
            class="collection-tab"
            :class="{ 'is-active': activeCollectionId === collection.id }"
            type="button"
            role="tab"
            :aria-selected="activeCollectionId === collection.id"
            @click="selectCollection(collection.id)"
          >
            <span>{{ collection.name }}</span>
            <ArchiveCounter :value="countForCollection(collection)" />
          </button>
        </div>
      </aside>

      <section
        class="archive-panel"
        aria-labelledby="archive-list-title"
        :aria-busy="loading"
      >
        <header class="panel-head">
          <div>
            <h2 id="archive-list-title">{{ activeFilterLabel }}</h2>
            <p>
              {{ refreshing ? '正在刷新收藏…' : `显示 ${filteredLinks.length} 条收藏` }}
            </p>
          </div>
          <div class="panel-tools">
            <div class="freshness">
              <span v-if="lastLoadedAt">
                更新于 <time class="num">{{ formatClock(lastLoadedAt) }}</time>
              </span>
              <button
                class="refresh-button"
                type="button"
                aria-label="刷新收藏"
                :disabled="loading || refreshing"
                @click="loadArchive({ quiet: true })"
              >
                <AppIcon name="refresh" :size="16" />
                <span>{{ refreshing ? '刷新中' : '刷新' }}</span>
              </button>
            </div>

            <div class="view-switch" role="group" aria-label="收藏显示方式">
              <button
                class="view-button"
                :class="{ 'is-active': linkView === 'grid' }"
                type="button"
                aria-label="网格视图"
                :aria-pressed="linkView === 'grid'"
                title="网格视图"
                @click="setLinkView('grid')"
              >
                <AppIcon name="grid" :size="16" />
                <span>网格</span>
              </button>
              <button
                class="view-button"
                :class="{ 'is-active': linkView === 'list' }"
                type="button"
                aria-label="列表视图"
                :aria-pressed="linkView === 'list'"
                title="列表视图"
                @click="setLinkView('list')"
              >
                <AppIcon name="list" :size="16" />
                <span>列表</span>
              </button>
            </div>
          </div>
        </header>

        <div v-if="loading" class="loading-grid" aria-label="正在加载收藏">
          <div v-for="index in 6" :key="index" class="loading-card">
            <span class="skeleton skeleton-art"></span>
            <span class="skeleton skeleton-meta"></span>
            <span class="skeleton skeleton-title"></span>
            <span class="skeleton skeleton-copy"></span>
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

        <ol
          v-else-if="linkView === 'grid'"
          class="link-grid"
          data-testid="link-grid"
        >
          <li v-for="link in filteredLinks" :key="link.id">
            <article class="archive-card" data-testid="archive-card">
              <router-link
                v-slot="{ href, navigate }"
                custom
                :to="`/links/${link.id}`"
              >
                <a
                  class="archive-card-main"
                  :href="href"
                  :aria-label="`打开收藏 ${link.title || link.originalUrl}`"
                  @click="openLink(link, navigate, $event)"
                >
                  <ArchiveArtwork
                    :link-id="link.id"
                    :title="link.title || ''"
                    :domain="formatDomain(link.originalUrl)"
                  />
                  <div class="archive-card-copy">
                    <p class="archive-meta">
                      <span>{{ formatDomain(link.originalUrl) }}</span>
                      <span aria-hidden="true">·</span>
                      <span>{{ formatRelativeTime(link.updatedAt) }}</span>
                    </p>
                    <h3 :style="{ 'view-transition-name': `link-title-${link.id}` }">
                      {{ link.title || '未命名网页' }}
                    </h3>
                    <p class="archive-description">
                      {{ link.description || '这条收藏尚未添加备注。' }}
                    </p>
                  </div>
                </a>
              </router-link>

              <footer class="archive-card-footer">
                <span class="collection-label">
                  <AppIcon name="archive" :size="14" />
                  {{ collectionNames.get(link.collectionId) || '未归入集合' }}
                </span>
                <a
                  class="external-action"
                  :href="link.originalUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  :aria-label="`在新窗口打开 ${link.title || link.originalUrl}`"
                  title="打开原始网页"
                >
                  <AppIcon name="external" :size="16" />
                </a>
              </footer>
            </article>
          </li>
        </ol>

        <ol v-else class="link-list" data-testid="link-list">
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
              class="external-action list-external"
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
    </div>

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
  padding: 56px 0 96px;
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
}

.heading-copy {
  min-width: 0;
}

.page-heading h1 {
  max-width: 12ch;
}

.page-heading p {
  max-width: 44em;
  margin: 18px 0 0;
  color: var(--muted);
  font-size: var(--font-size-body);
  line-height: var(--line-height-body);
}

.primary-action {
  min-height: var(--control-height);
  padding: 0 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: none;
  border: 1px solid var(--text);
  border-radius: var(--radius-pill);
  color: var(--on-primary);
  background: var(--text);
  font-weight: 500;
  transition:
    background-color var(--motion-fast) var(--ease-out),
    border-color var(--motion-fast) var(--ease-out);
}

.primary-action:hover {
  border-color: var(--text-hover);
  background: var(--text-hover);
}

.metrics-strip {
  margin-top: 34px;
  padding: 16px 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-block: 1px solid var(--border);
}

.metric {
  min-width: 0;
  padding: 0 24px;
  display: grid;
  gap: 6px;
}

.metric:first-child {
  padding-left: 0;
}

.metric:not(:first-child) {
  border-left: 1px solid var(--rule);
}

.metric > span {
  color: var(--muted);
  font-size: var(--font-size-small);
}

.metric strong {
  color: var(--text);
  font-size: 26px;
  line-height: 1.1;
  font-weight: 500;
  letter-spacing: -0.03em;
}

.metric-primary strong {
  color: var(--accent);
}

.archive-workspace {
  margin-top: 48px;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
  gap: 32px;
}

.collection-rail {
  position: sticky;
  top: calc(var(--topbar-height) + 28px);
}

.rail-heading h2 {
  font-size: var(--font-size-small);
  font-weight: 500;
}

.rail-heading p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: var(--font-size-caption);
}

.collection-list {
  margin-top: 16px;
  display: grid;
  gap: 3px;
}

.collection-tab {
  width: 100%;
  min-height: 38px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 0;
  border-radius: var(--radius-pill);
  color: var(--muted);
  background: transparent;
  text-align: left;
  font-size: var(--font-size-small);
}

.collection-tab > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-tab:hover {
  color: var(--text);
  background: var(--raised);
}

.collection-tab.is-active {
  color: var(--text);
  background: var(--active);
}

.collection-tab :deep(span) {
  color: inherit;
}

.archive-panel {
  min-width: 0;
}

.panel-head {
  min-height: 58px;
  padding-bottom: 18px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--border);
}

.panel-head h2 {
  font-size: var(--font-size-section);
}

.panel-head p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: var(--font-size-small);
}

.panel-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}

.freshness {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}

.refresh-button {
  min-height: 34px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  color: var(--muted);
  background: transparent;
  font-size: var(--font-size-caption);
}

.refresh-button:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--control);
  background: var(--raised);
}

.refresh-button:disabled {
  cursor: wait;
  opacity: 0.62;
}

.view-switch {
  padding: 3px;
  display: flex;
  align-items: center;
  gap: 2px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
}

.view-button {
  min-height: 30px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: var(--radius-pill);
  color: var(--muted);
  background: transparent;
  font-size: var(--font-size-caption);
}

.view-button:hover {
  color: var(--text);
}

.view-button.is-active {
  color: var(--text);
  background: var(--active);
}

.link-grid {
  margin: 24px 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: stretch;
  gap: var(--gap);
  list-style: none;
}

.link-grid > li {
  min-width: 0;
}

.archive-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
  transition:
    border-color var(--motion-fast) var(--ease-out),
    transform var(--motion-fast) var(--ease-out);
}

.archive-card:hover,
.archive-card:focus-within {
  border-color: var(--control-hover);
  transform: translateY(-2px);
}

.archive-card-main {
  display: block;
  color: inherit;
  text-decoration: none;
}

.archive-card-copy {
  padding: 17px 18px 15px;
}

.archive-meta {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-size-caption);
  line-height: 1.4;
  white-space: nowrap;
}

.archive-meta span:first-child {
  overflow: hidden;
  color: var(--accent-strong);
  text-overflow: ellipsis;
}

.archive-card h3 {
  margin: 9px 0 0;
  overflow: hidden;
  color: var(--text);
  font-size: 17px;
  font-weight: 550;
  letter-spacing: -0.015em;
  line-height: 1.42;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-description {
  display: -webkit-box;
  margin: 8px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-size-small);
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.archive-card-footer {
  min-height: 46px;
  margin-top: auto;
  padding: 8px 12px 8px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--rule);
}

.collection-label {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-size-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.external-action {
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
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

.loading-grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--gap);
}

.loading-card {
  min-height: 300px;
  padding-bottom: 18px;
  display: grid;
  align-content: start;
  gap: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.skeleton {
  display: block;
  background: var(--active);
}

.skeleton-art {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 0;
}

.skeleton-meta,
.skeleton-title,
.skeleton-copy {
  margin-inline: 18px;
  border-radius: 4px;
}

.skeleton-meta {
  width: 48%;
  height: 10px;
}

.skeleton-title {
  width: 72%;
  height: 16px;
}

.skeleton-copy {
  width: 82%;
  height: 12px;
}

.block-state {
  min-height: 300px;
  margin-top: 24px;
  padding: 36px;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  border: 1px dashed var(--control);
  border-radius: var(--radius-panel);
}

.state-mark {
  width: 38px;
  height: 38px;
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
  font-size: 17px;
  font-weight: 550;
}

.block-state p {
  max-width: 52em;
  margin: 8px 0 12px;
  color: var(--muted);
  font-size: var(--font-size-small);
  line-height: 1.6;
}

.block-error {
  border-style: solid;
  border-color: var(--negative-border);
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
  margin: 18px 0 0;
  padding: 12px 14px;
  border: 1px solid var(--warning);
  border-radius: var(--radius-control);
  color: var(--warning);
  background: var(--warning-soft);
  font-size: var(--font-size-small);
}

.link-list {
  margin: 24px 0 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--border);
}

.link-row {
  min-height: var(--row-height);
  padding: 12px 4px;
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(120px, 0.32fr) 40px;
  align-items: center;
  gap: 20px;
  border-bottom: 1px solid var(--rule);
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
  width: 38px;
  height: 42px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: var(--text);
  background: var(--art-peach);
  font-family: var(--font-display);
  font-size: 15px;
  font-weight: 500;
}

.link-copy {
  min-width: 0;
}

.link-copy h3 {
  margin: 0;
  overflow: hidden;
  color: var(--text);
  font-size: 15px;
  line-height: 1.45;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-copy p {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: var(--font-size-small);
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
  font-size: var(--font-size-small);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-external {
  justify-self: end;
}

@media (max-width: 1120px) {
  .archive-workspace {
    grid-template-columns: 196px minmax(0, 1fr);
    gap: 24px;
  }

  .link-grid,
  .loading-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .links-view {
    padding-top: 38px;
  }

  .archive-workspace {
    display: block;
    margin-top: 34px;
  }

  .collection-rail {
    position: static;
  }

  .collection-list {
    display: flex;
    overflow-x: auto;
    padding-bottom: 4px;
  }

  .collection-tab {
    width: auto;
    flex: none;
    white-space: nowrap;
  }

  .archive-panel {
    margin-top: 26px;
  }
}

@media (max-width: 640px) {
  .links-view {
    padding-top: 30px;
  }

  .page-heading {
    align-items: flex-end;
  }

  .page-heading p {
    max-width: 25em;
    margin-top: 14px;
  }

  .primary-action {
    min-width: 44px;
    min-height: 44px;
    padding: 0 14px;
  }

  .metrics-strip {
    padding: 13px 0;
  }

  .metric {
    padding: 0 12px;
  }

  .metric strong {
    font-size: 22px;
  }

  .panel-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }

  .panel-tools {
    width: 100%;
    justify-content: space-between;
  }

  .freshness > span {
    display: none;
  }

  .refresh-button {
    width: 40px;
    min-height: 40px;
    padding: 0;
    justify-content: center;
  }

  .refresh-button span,
  .view-button span {
    display: none;
  }

  .view-button {
    width: 40px;
    min-height: 34px;
    padding: 0;
    justify-content: center;
  }

  .link-grid,
  .loading-grid {
    grid-template-columns: 1fr;
  }

  .archive-description {
    -webkit-line-clamp: 3;
  }

  .block-state {
    min-height: 260px;
    padding: 28px 20px;
  }

  .link-row {
    grid-template-columns: minmax(0, 1fr) 44px;
    gap: 10px;
    padding: 14px 0;
  }

  .collection-cell {
    grid-column: 1;
    padding-left: 50px;
    margin-top: -8px;
  }

  .list-external {
    grid-column: 2;
    grid-row: 1 / span 2;
    min-width: 44px;
    min-height: 44px;
  }
}

@media (max-width: 420px) {
  .primary-action span {
    display: none;
  }

  .metric {
    padding: 0 9px;
  }

  .metric strong {
    font-size: 20px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .archive-card {
    transform: none;
  }
}
</style>
