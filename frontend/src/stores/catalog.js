import { defineStore } from 'pinia'
import api from '../api'

export const useCatalogStore = defineStore('catalog', {
  state: () => ({
    collections: [],
    tags: [],
    collectionsLoaded: false,
    tagsLoaded: false,
    loadingCollections: false,
    loadingTags: false,
    collectionError: '',
    tagError: '',
  }),
  getters: {
    collectionName: (state) => (id) =>
      state.collections.find((collection) => collection.id === id)?.name || '未归档集合',
  },
  actions: {
    async load(force = false) {
      await Promise.all([
        this.refreshCollections(force),
        this.refreshTags(force),
      ])
    },
    async refreshCollections(force = false) {
      if (!force && this.collectionsLoaded) return
      this.loadingCollections = true
      this.collectionError = ''
      try {
        const response = await api.get('/collections')
        this.collections = response.data
        this.collectionsLoaded = true
      } catch (error) {
        this.collectionError = error.message
        throw error
      } finally {
        this.loadingCollections = false
      }
    },
    async refreshTags(force = false) {
      if (!force && this.tagsLoaded) return
      this.loadingTags = true
      this.tagError = ''
      try {
        const response = await api.get('/tags')
        this.tags = response.data
        this.tagsLoaded = true
      } catch (error) {
        this.tagError = error.message
        throw error
      } finally {
        this.loadingTags = false
      }
    },
  },
})
