import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

/* 主题在应用挂载前应用，避免闪色；默认清新薄荷（浅色），可在 工作台 → 通用设置 切换 */
document.documentElement.setAttribute('data-theme', localStorage.getItem('af-theme') || 'mint')

createApp(App).mount('#app')
