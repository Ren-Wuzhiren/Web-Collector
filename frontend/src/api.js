import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 35000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('webcollector-token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('webcollector-token')
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    const message =
      error.response?.data?.message ||
      error.message ||
      '请求失败，请稍后重试'
    return Promise.reject(new Error(message))
  },
)

export default api
