<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const links = ref([])
const collections = ref([])
const loading = ref(false)
const saving = ref(false)
const job = ref(null)
const assets = ref([])
let pollTimer = null

const form = reactive({
  url: '',
  collectionId: null,
  title: '',
  description: '',
})

onMounted(load)
onBeforeUnmount(stopPolling)

async function load() {
  loading.value = true
  try {
    const [linkResponse, collectionResponse] = await Promise.all([
      api.get('/links'),
      api.get('/collections'),
    ])
    links.value = linkResponse.data
    collections.value = collectionResponse.data
    if (!form.collectionId && collections.value.length) {
      form.collectionId = collections.value[0].id
    }
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const response = await api.post('/links', {
      ...form,
      tagIds: [],
    })
    links.value.unshift(response.data.link)
    form.url = ''
    form.title = ''
    form.description = ''
    ElMessage.success('已保存，正在抓取网页')
    pollJob(response.data.job.id)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    saving.value = false
  }
}

function pollJob(jobId) {
  stopPolling()
  const tick = async () => {
    try {
      const response = await api.get(`/jobs/${jobId}`)
      job.value = response.data
      if (['SUCCEEDED', 'FAILED'].includes(job.value.status)) {
        stopPolling()
        await loadAssets(job.value.linkId)
        if (job.value.status === 'SUCCEEDED') {
          ElMessage.success('网页快照已完成')
        } else {
          ElMessage.error(job.value.errorMessage || '抓取失败')
        }
      }
    } catch (error) {
      stopPolling()
      ElMessage.error(error.message)
    }
  }
  tick()
  pollTimer = window.setInterval(tick, 3000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function selectLink(link) {
  job.value = null
  await loadAssets(link.id)
}

async function loadAssets(linkId) {
  try {
    const response = await api.get('/assets', { params: { linkId } })
    assets.value = response.data
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function openAsset(asset) {
  try {
    const response = await api.get(`/assets/${asset.id}`, { responseType: 'blob' })
    const url = URL.createObjectURL(response)
    window.open(url, '_blank', 'noopener')
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="page-card links-page">
    <el-card>
      <template #header>
        <div class="card-title">
          <span>保存网页</span>
          <span class="muted">后台会生成 HTML、PDF 与截图</span>
        </div>
      </template>
      <el-form label-position="top">
        <el-form-item label="网页 URL">
          <el-input v-model="form.url" placeholder="https://example.com/article" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="集合">
            <el-select v-model="form.collectionId" placeholder="选择集合">
              <el-option
                v-for="collection in collections"
                :key="collection.id"
                :label="collection.name"
                :value="collection.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="标题（可选）">
            <el-input v-model="form.title" />
          </el-form-item>
        </div>
        <el-form-item label="描述（可选）">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!form.url" @click="save">
          保存并抓取
        </el-button>
      </el-form>
      <el-alert
        v-if="job"
        class="job-alert"
        :type="job.status === 'FAILED' ? 'error' : job.status === 'SUCCEEDED' ? 'success' : 'info'"
        :closable="false"
        :title="`抓取任务：${job.status}`"
        :description="job.errorMessage || `已尝试 ${job.attemptCount} 次`"
      />
    </el-card>

    <el-card class="content-card">
      <template #header>
        <div class="card-title">
          <span>我的收藏</span>
          <el-button link @click="load">刷新</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="links" @row-click="selectLink">
        <el-table-column prop="title" label="标题" min-width="180">
          <template #default="{ row }">{{ row.title || '未命名链接' }}</template>
        </el-table-column>
        <el-table-column prop="originalUrl" label="地址" min-width="300" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" width="220" />
      </el-table>
    </el-card>

    <el-card v-if="assets.length" class="content-card">
      <template #header>快照资产</template>
      <div class="asset-grid">
        <div v-for="asset in assets" :key="asset.id" class="asset-item">
          <div>
            <strong>{{ asset.format }}</strong>
            <div class="muted">{{ asset.mimeType }} · {{ formatBytes(asset.fileSizeBytes) }}</div>
          </div>
          <el-button type="primary" plain @click="openAsset(asset)">打开</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.links-page {
  display: grid;
  gap: 18px;
}
.card-title {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.job-alert {
  margin-top: 18px;
}
.content-card {
  margin-top: 2px;
}
.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.asset-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 14px;
}
@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
