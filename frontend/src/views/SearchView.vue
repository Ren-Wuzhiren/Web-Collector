<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const query = ref('')
const results = ref([])
const loading = ref(false)

async function search() {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const response = await api.get('/search', { params: { q: query.value } })
    results.value = response.data
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-card search-page">
    <el-card>
      <template #header>搜索标题、描述与正文</template>
      <el-input
        v-model="query"
        size="large"
        clearable
        placeholder="输入中英文关键词"
        @keyup.enter="search"
      >
        <template #append>
          <el-button :loading="loading" @click="search">搜索</el-button>
        </template>
      </el-input>
    </el-card>
    <el-card class="result-card">
      <el-empty v-if="!loading && !results.length" description="没有匹配结果" />
      <div v-else class="results">
        <a
          v-for="item in results"
          :key="item.id"
          class="result"
          :href="item.originalUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <strong>{{ item.title || item.originalUrl }}</strong>
          <span class="muted">{{ item.description || item.originalUrl }}</span>
        </a>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.search-page {
  display: grid;
  gap: 18px;
}
.result-card {
  min-height: 260px;
}
.results {
  display: grid;
  gap: 12px;
}
.result {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
}
.result:hover {
  border-color: #409eff;
}
</style>
