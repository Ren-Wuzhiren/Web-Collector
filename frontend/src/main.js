import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import '@fontsource-variable/noto-sans-sc'
import '@fontsource-variable/noto-serif-sc'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'

createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app')
