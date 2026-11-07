<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import ArchiveCounter from '../components/ArchiveCounter.vue'

const tags = ref([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const usageFilter = ref('all')
const createName = ref('')
const creating = ref(false)
const editingId = ref(null)
const editingName = ref('')
const savingId = ref(null)
const deletingId = ref(null)
const nameInput = ref(null)
let editSequence = 0

const usedCount = computed(() => tags.value.filter((tag) => tag.linkCount > 0).length)
const unusedCount = computed(() => tags.value.length - usedCount.value)
const maxUsage = computed(() => Math.max(1, ...tags.value.map((tag) => tag.linkCount)))

const filteredTags = computed(() => {
  const normalizedQuery = query.value.trim().toLocaleLowerCase('zh-CN')
  return tags.value.filter((tag) => {
    const matchesQuery =
      !normalizedQuery || tag.name.toLocaleLowerCase('zh-CN').includes(normalizedQuery)
    const matchesUsage =
      usageFilter.value === 'all' ||
      (usageFilter.value === 'used' && tag.linkCount > 0) ||
      (usageFilter.value === 'unused' && tag.linkCount === 0)
    return matchesQuery && matchesUsage
  })
})

const pageSummary = computed(() => {
  if (loading.value) return '正在读取当前账号的标签目录。'
  if (loadError.value) return '标签目录暂时无法读取，已有内容不会受到影响。'
  if (!tags.value.length) return '还没有标签。先创建一个标签，再用它整理收藏。'
  const unusedText = unusedCount.value ? `，其中 ${unusedCount.value} 个还没有关联收藏` : ''
  return `共 ${tags.value.length} 个标签，${usedCount.value} 个正在被收藏使用${unusedText}。`
})

const resultSummary = computed(() => {
  if (query.value.trim() || usageFilter.value !== 'all') {
    return `当前显示 ${filteredTags.value.length} 个标签`
  }
  return `共 ${tags.value.length} 个标签`
})

onMounted(loadTags)

async function loadTags() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.get('/tags')
    tags.value = [...response.data].sort((left, right) => left.id - right.id)
  } catch (error) {
    loadError.value = error.message
  } finally {
    loading.value = false
  }
}

async function createTag() {
  const name = createName.value.trim()
  if (!name) {
    ElMessage.warning('请输入标签名称')
    nameInput.value?.focus()
    return
  }

  creating.value = true
  try {
    const response = await api.post('/tags', { name })
    tags.value = [...tags.value, response.data].sort((left, right) => left.id - right.id)
    createName.value = ''
    ElMessage.success(`已创建标签“${response.data.name}”`)
    nameInput.value?.focus()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    creating.value = false
  }
}

async function startRename(tag) {
  editingId.value = tag.id
  editingName.value = tag.name
  editSequence += 1
  const sequence = editSequence
  await nextTick()
  if (sequence === editSequence) {
    document.getElementById(`rename-tag-${tag.id}`)?.focus()
  }
}

function cancelRename() {
  editingId.value = null
  editingName.value = ''
  editSequence += 1
}

async function saveRename(tag) {
  const name = editingName.value.trim()
  if (!name) {
    ElMessage.warning('标签名称不能为空')
    document.getElementById(`rename-tag-${tag.id}`)?.focus()
    return
  }
  if (name === tag.name) {
    cancelRename()
    return
  }

  savingId.value = tag.id
  try {
    const response = await api.patch(`/tags/${tag.id}`, { name })
    tags.value = tags.value.map((item) => (item.id === tag.id ? response.data : item))
    cancelRename()
    ElMessage.success('标签名称已更新')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    savingId.value = null
  }
}

async function deleteTag(tag) {
  try {
    await ElMessageBox.confirm(
      `删除“${tag.name}”？收藏不会被删除，只会移除这个标签。`,
      '删除标签',
      {
        confirmButtonText: '删除标签',
        cancelButtonText: '取消',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    return
  }

  deletingId.value = tag.id
  try {
    await api.delete(`/tags/${tag.id}`)
    tags.value = tags.value.filter((item) => item.id !== tag.id)
    if (editingId.value === tag.id) cancelRename()
    ElMessage.success('标签已删除，收藏仍然保留')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    deletingId.value = null
  }
}

function setUsageFilter(filter) {
  usageFilter.value = filter
}

function formatCreatedAt(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(value))
}

function usageWidth(tag) {
  if (!tag.linkCount) return '0%'
  return `${Math.max(8, (tag.linkCount / maxUsage.value) * 100)}%`
}
</script>

<template>
  <main class="tags-page">
    <header class="page-heading">
      <div class="heading-copy">
        <h1>标签</h1>
        <p>{{ pageSummary }}</p>
      </div>
      <button
        class="secondary-button"
        type="button"
        :disabled="loading"
        @click="loadTags"
      >
        {{ loading ? '读取中' : '刷新目录' }}
      </button>
    </header>

    <div class="tags-workspace">
    <aside class="create-panel" aria-labelledby="create-tag-title">
      <div class="create-copy">
        <h2 id="create-tag-title">新建标签</h2>
        <p>用简短、稳定的名称整理收藏；同名标签不会被重复创建。</p>
      </div>
      <form class="create-form" @submit.prevent="createTag">
        <div class="field-group">
          <label for="new-tag-name">标签名称</label>
          <div class="joined-control">
            <el-input
              id="new-tag-name"
              ref="nameInput"
              v-model="createName"
              maxlength="64"
              show-word-limit
              clearable
              placeholder="例如：Spring Boot"
              @keyup.enter="createTag"
            />
            <button
              class="primary-button"
              type="submit"
              :disabled="creating || !createName.trim()"
            >
              {{ creating ? '创建中' : '创建标签' }}
            </button>
          </div>
        </div>
      </form>
      <dl class="tag-stats">
        <div>
          <dt>全部</dt>
          <dd class="num">{{ tags.length }}</dd>
        </div>
        <div>
          <dt>使用中</dt>
          <dd class="num">{{ usedCount }}</dd>
        </div>
        <div>
          <dt>未使用</dt>
          <dd class="num">{{ unusedCount }}</dd>
        </div>
      </dl>
    </aside>

    <section class="tag-ledger" aria-labelledby="tag-ledger-title">
      <header class="ledger-header">
        <div>
          <h2 id="tag-ledger-title">标签目录</h2>
          <p>{{ resultSummary }}</p>
        </div>
        <div class="ledger-tools">
          <div class="filter-group" role="tablist" aria-label="按使用情况筛选标签">
            <button
              v-for="filter in [
                { value: 'all', label: '全部' },
                { value: 'used', label: '使用中' },
                { value: 'unused', label: '未使用' },
              ]"
              :key="filter.value"
              class="filter-button"
              type="button"
              role="tab"
              :aria-selected="usageFilter === filter.value"
              :class="{ active: usageFilter === filter.value }"
              @click="setUsageFilter(filter.value)"
            >
              {{ filter.label }}
            </button>
          </div>
          <label class="search-field">
            <span class="visually-hidden">查找标签</span>
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <circle cx="8.5" cy="8.5" r="5.5" />
              <path d="m12.6 12.6 4 4" />
            </svg>
            <input v-model="query" type="search" placeholder="查找标签" />
          </label>
        </div>
      </header>

      <div v-if="loadError" class="state-panel error-panel" role="alert">
        <div>
          <h3>标签目录没有加载出来</h3>
          <p>{{ loadError }}</p>
        </div>
        <button class="secondary-button" type="button" @click="loadTags">重试</button>
      </div>

      <div v-else-if="loading" class="loading-table" aria-label="正在加载标签">
        <div class="loading-row loading-head">
          <span v-for="cell in 4" :key="cell"></span>
        </div>
        <div v-for="row in 5" :key="row" class="loading-row">
          <span></span>
          <span></span>
          <span></span>
          <span></span>
        </div>
      </div>

      <div v-else-if="!tags.length" class="state-panel empty-panel">
        <div class="empty-mark" aria-hidden="true">
          <span>#</span>
        </div>
        <div>
          <h3>还没有标签</h3>
          <p>创建标签后，可以在保存和详情页里整理收藏。</p>
        </div>
        <button
          class="text-button"
          type="button"
          @click="nameInput?.focus()"
        >
          创建第一个标签
        </button>
      </div>

      <div v-else-if="!filteredTags.length" class="state-panel empty-panel">
        <div>
          <h3>没有符合条件的标签</h3>
          <p>换一个关键词，或切回“全部”查看完整目录。</p>
        </div>
        <button
          class="text-button"
          type="button"
          @click="query = ''; usageFilter = 'all'"
        >
          清除筛选
        </button>
      </div>

      <div v-else class="table-frame">
        <table>
          <thead>
            <tr>
              <th scope="col">标签</th>
              <th scope="col" class="usage-column">使用情况</th>
              <th scope="col">创建时间</th>
              <th scope="col" class="action-column">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="tag in filteredTags"
              :key="tag.id"
              :class="{ editing: editingId === tag.id }"
            >
              <td class="name-cell">
                <template v-if="editingId === tag.id">
                  <div class="rename-control">
                    <el-input
                      :id="`rename-tag-${tag.id}`"
                      v-model="editingName"
                      maxlength="64"
                      size="small"
                      @keyup.enter="saveRename(tag)"
                      @keyup.esc="cancelRename"
                    />
                    <button
                      class="text-button save"
                      type="button"
                      :disabled="savingId === tag.id"
                      @click="saveRename(tag)"
                    >
                      {{ savingId === tag.id ? '保存中' : '保存' }}
                    </button>
                    <button class="text-button" type="button" @click="cancelRename">
                      取消
                    </button>
                  </div>
                </template>
                <span v-else class="tag-name">{{ tag.name }}</span>
              </td>
              <td class="usage-cell">
                <div class="usage-value">
                  <strong>{{ tag.linkCount }}</strong>
                  <span>条收藏</span>
                </div>
                <div
                  class="usage-track"
                  :aria-label="`${tag.name} 被 ${tag.linkCount} 条收藏使用`"
                >
                  <span :style="{ width: usageWidth(tag) }"></span>
                </div>
              </td>
              <td class="date-cell">{{ formatCreatedAt(tag.createdAt) }}</td>
              <td class="actions-cell">
                <button
                  class="text-button"
                  type="button"
                  :disabled="savingId === tag.id || deletingId === tag.id"
                  @click="startRename(tag)"
                >
                  改名
                </button>
                <button
                  class="text-button danger"
                  type="button"
                  :disabled="savingId === tag.id || deletingId === tag.id"
                  @click="deleteTag(tag)"
                >
                  {{ deletingId === tag.id ? '删除中' : '删除' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer v-if="tags.length && !loading && !loadError" class="ledger-footer">
        <span>{{ resultSummary }}</span>
        <span><ArchiveCounter :value="unusedCount" /> 个标签尚未关联收藏</span>
      </footer>
    </section>
    </div>
  </main>
</template>

<style scoped>
.tags-page {
  width: min(var(--content-width), calc(100% - (2 * var(--page-pad))));
  margin: 0 auto;
  padding: 56px 0 96px;
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 40px;
}

.heading-copy {
  min-width: 0;
}

h1,
h2,
h3,
p {
  margin: 0;
}

h1 {
  font-size: var(--font-size-title);
  line-height: var(--line-height-title);
  font-weight: 500;
  letter-spacing: -0.03em;
}

.page-heading p {
  max-width: 52em;
  margin-top: 14px;
  color: var(--muted);
  font-size: var(--font-size-body);
  line-height: var(--line-height-body);
}

.tags-workspace {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  align-items: start;
  gap: 32px;
}

.create-panel,
.tag-ledger {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
}

.create-panel {
  position: sticky;
  top: calc(var(--topbar-height) + 24px);
  padding: 24px;
}

.create-copy h2,
.ledger-header h2 {
  font-size: 18px;
  line-height: 1.3;
  font-weight: 500;
}

.create-copy p,
.ledger-header p {
  margin-top: 4px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

.field-group {
  display: grid;
  gap: 8px;
  margin-top: 24px;
}

.field-group > label {
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
}

.joined-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.joined-control :deep(.el-input__wrapper) {
  min-height: 36px;
  box-shadow: 0 0 0 1px var(--control) inset; /* taste-skill-ignore: Element Plus control border */
}

.joined-control :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--text) inset; /* taste-skill-ignore: Element Plus focus border */
}

.joined-control :deep(.el-input__count) {
  color: var(--muted);
}

.primary-button,
.secondary-button {
  min-height: 36px;
  border-radius: var(--radius-pill);
  padding: 0 16px;
  font-size: 14px;
  font-weight: 500;
  transition:
    background-color 160ms ease,
    border-color 160ms ease,
    color 160ms ease;
}

.primary-button {
  border: 1px solid var(--text);
  color: var(--on-primary);
  background: var(--text);
}

.primary-button:hover:not(:disabled) {
  border-color: var(--text);
  background: var(--text);
}

.primary-button:disabled {
  cursor: not-allowed;
  border-color: var(--control);
  color: var(--muted);
  background: var(--active);
}

.secondary-button {
  flex: none;
  border: 1px solid var(--control);
  color: var(--text);
  background: var(--surface);
}

.secondary-button:hover:not(:disabled) {
  border-color: var(--muted);
  background: var(--raised);
}

.tag-ledger {
  min-width: 0;
  margin-top: 0;
  overflow: hidden;
}

.tag-stats {
  margin: 24px 0 0;
  padding-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--rule);
}

.tag-stats div {
  min-width: 0;
}

.tag-stats div + div {
  padding-left: 14px;
  border-left: 1px solid var(--rule);
}

.tag-stats dt {
  color: var(--muted);
  font-size: var(--font-size-caption);
}

.tag-stats dd {
  margin: 5px 0 0;
  color: var(--text);
  font-size: 22px;
  font-weight: 500;
  line-height: 1;
}

.ledger-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 80px;
  padding: 20px 24px 16px;
}

.ledger-tools {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border-radius: var(--radius-pill);
  background: var(--raised);
}

.filter-button {
  min-height: 28px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  padding: 0 11px;
  color: var(--muted);
  background: transparent;
  font-size: 13px;
}

.filter-button:hover {
  color: var(--text);
}

.filter-button.active {
  border-color: var(--border);
  color: var(--text);
  background: var(--surface);
  font-weight: 500;
}

.search-field {
  position: relative;
  display: flex;
  align-items: center;
  width: 208px;
  height: 36px;
  border: 1px solid var(--control);
  border-radius: var(--radius-control);
  color: var(--muted);
  background: var(--surface);
}

.search-field:focus-within {
  border-color: var(--text);
  outline: 2px solid color-mix(in srgb, var(--text) 12%, transparent);
  outline-offset: 1px;
}

.search-field svg {
  width: 16px;
  height: 16px;
  margin-left: 11px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.5;
}

.search-field input {
  min-width: 0;
  width: 100%;
  height: 100%;
  border: 0;
  outline: 0;
  padding: 0 10px 0 8px;
  color: var(--text);
  background: transparent;
  font-size: 13px;
}

.search-field input::placeholder {
  color: var(--muted);
}

.table-frame {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

th {
  height: 38px;
  padding: 0 24px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 400;
  text-align: left;
}

th:first-child,
td:first-child {
  width: 42%;
  padding-left: 24px;
}

th:nth-child(2),
td:nth-child(2) {
  width: 23%;
}

th:nth-child(3),
td:nth-child(3) {
  width: 19%;
}

th:last-child,
td:last-child {
  width: 16%;
  padding-right: 24px;
}

tbody tr {
  border-top: 1px solid var(--rule);
  transition: background-color var(--motion-fast) var(--ease-out);
}

tbody tr:hover,
tbody tr:focus-within,
tbody tr.editing {
  background: var(--raised);
}

td {
  height: 68px;
  padding: 12px 24px;
  color: var(--text);
  font-size: 14px;
  vertical-align: middle;
}

.tag-name {
  display: block;
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-cell {
  padding-right: 40px;
}

.usage-value {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 5px;
  font-variant-numeric: tabular-nums;
}

.usage-value strong {
  font-size: 16px;
  font-weight: 500;
}

.usage-value span {
  color: var(--muted);
  font-size: 12px;
}

.usage-track {
  height: 2px;
  margin-top: 7px;
  overflow: hidden;
  background: var(--rule);
}

.usage-track span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.date-cell {
  color: var(--muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.actions-cell {
  text-align: right;
  white-space: nowrap;
}

.text-button {
  min-height: 32px;
  border: 0;
  border-radius: var(--radius-pill);
  padding: 0 7px;
  color: var(--muted);
  background: transparent;
  font-size: 13px;
  font-weight: 500;
}

.text-button:hover:not(:disabled) {
  color: var(--text);
  background: var(--active);
}

.text-button.danger:hover:not(:disabled) {
  color: var(--negative);
  background: var(--negative-soft);
}

.text-button.save {
  color: var(--accent);
}

.text-button:disabled {
  cursor: wait;
  opacity: 0.55;
}

.actions-cell .text-button + .text-button {
  margin-left: 4px;
}

.rename-control {
  display: flex;
  align-items: center;
  gap: 4px;
}

.rename-control :deep(.el-input) {
  max-width: 240px;
}

.ledger-footer {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  min-height: 46px;
  padding: 13px 24px;
  border-top: 1px solid var(--rule);
  color: var(--muted);
  background: var(--raised);
  font-size: 12px;
}

.state-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 180px;
  margin: 0 24px 24px;
  border: 1px dashed var(--control);
  border-radius: var(--radius-control);
  padding: 24px;
}

.state-panel h3 {
  font-size: 15px;
  line-height: 24px;
  font-weight: 500;
}

.state-panel p {
  max-width: 620px;
  margin-top: 4px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.error-panel {
  border-style: solid;
  border-color: var(--negative-border);
}

.empty-panel {
  justify-content: flex-start;
}

.empty-mark {
  display: grid;
  width: 48px;
  height: 48px;
  flex: none;
  place-items: center;
  border-radius: 50%;
  color: var(--muted);
  background: var(--raised);
  font-size: 22px;
  font-weight: 500;
}

.empty-panel .text-button {
  margin-left: auto;
}

.loading-table {
  padding-bottom: 12px;
}

.loading-row {
  display: grid;
  grid-template-columns: 42% 23% 19% 16%;
  min-height: 68px;
  align-items: center;
  border-top: 1px solid var(--rule);
  padding: 0 24px;
}

.loading-row span {
  display: block;
  width: 56%;
  height: 12px;
  border-radius: 3px;
  background: var(--rule);
}

.loading-row span:nth-child(2),
.loading-row span:nth-child(4) {
  width: 36%;
}

.loading-head {
  min-height: 38px;
  border-top: 0;
}

.loading-head span {
  height: 8px;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (max-width: 900px) {
  .tags-page {
    padding-top: 38px;
  }

  .tags-workspace {
    display: block;
  }

  .create-panel {
    position: static;
    gap: 16px;
  }

  .tag-ledger {
    margin-top: 24px;
  }

  .ledger-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .ledger-tools {
    width: 100%;
    justify-content: space-between;
  }

  .search-field {
    flex: 1;
    max-width: 280px;
  }
}

@media (max-width: 640px) {
  .tags-page {
    padding: 30px 0 56px;
  }

  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .page-heading p {
    max-width: 34em;
  }

  .joined-control,
  .ledger-tools {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }

  .primary-button,
  .secondary-button {
    min-height: 44px;
  }

  .filter-group {
    justify-content: space-between;
  }

  .filter-button {
    flex: 1;
    min-height: 38px;
  }

  .search-field {
    width: 100%;
    max-width: none;
    height: 44px;
  }

  table,
  tbody,
  tr,
  td {
    display: block;
  }

  thead {
    display: none;
  }

  tbody tr {
    padding: 16px 20px;
  }

  td,
  th:first-child,
  td:first-child,
  th:nth-child(2),
  td:nth-child(2),
  th:nth-child(3),
  td:nth-child(3),
  th:last-child,
  td:last-child {
    width: 100%;
    height: auto;
    padding: 0;
    text-align: left;
  }

  .tag-name {
    font-size: 15px;
  }

  .usage-cell {
    margin-top: 12px;
  }

  .usage-value {
    justify-content: flex-start;
  }

  .date-cell {
    margin-top: 8px;
  }

  .actions-cell {
    margin-top: 12px;
  }

  .actions-cell .text-button {
    min-height: 44px;
  }

  .ledger-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .state-panel {
    align-items: flex-start;
    flex-direction: column;
  }

  .empty-panel .text-button {
    margin-left: 0;
  }
}
</style>
