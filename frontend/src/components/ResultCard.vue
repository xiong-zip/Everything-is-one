<template>
  <div class="step-result">
    <div class="result-card">
      <div class="rc-label">执行结果</div>

      <!-- 天气 -->
      <div v-if="result.type === 'weather'" class="weather">
        <div class="weather-main">
          <div>
            <div class="weather-temp">{{ r.tempLo }}° / {{ r.tempHi }}°</div>
            <div class="weather-cond">{{ r.city }} · {{ r.condition }}</div>
            <div class="weather-sub">{{ r.date }} · 体感 {{ r.feels }}°</div>
          </div>
        </div>
        <div class="weather-detail">
          <span><span class="wd-key">风力</span>{{ r.wind }}</span>
          <span><span class="wd-key">湿度</span>{{ r.humidity }}</span>
          <span><span class="wd-key">紫外线</span>{{ r.uv }}</span>
        </div>
      </div>

      <!-- 股票 -->
      <div v-else-if="result.type === 'stock'" class="stock-grid">
        <div class="stock-price" :class="r.trend === 'down' ? 'down' : 'up'">
          {{ r.price }} <small>{{ r.change }}</small>
        </div>
        <div>
          <div class="stock-name">
            {{ r.name }} <code style="font-size:11px;color:var(--ink-3)">{{ r.symbol }}</code>
          </div>
          <div class="stock-sub">今开 {{ r.open }} · 成交额 {{ r.amount }} · 换手 {{ r.turnover }}</div>
        </div>
      </div>

      <!-- 交通 -->
      <div v-else-if="result.type === 'transit'" class="weather">
        <div class="weather-main">
          <div>
            <div class="weather-temp" style="font-size:1.4rem">{{ r.mode }}</div>
            <div class="weather-cond">{{ r.duration }}</div>
          </div>
        </div>
        <div class="weather-detail">
          <span><span class="wd-key">票价</span>{{ r.price }}</span>
          <span><span class="wd-key">班次</span>{{ r.freq }}</span>
        </div>
      </div>

      <!-- 列表 -->
      <div v-else-if="result.type === 'list'" class="list-block">
        <div v-for="(item, i) in result.list || []" :key="i" class="list-row">
          <span class="n">{{ String(i + 1).padStart(2, '0') }}</span>
          <span>{{ item }}</span>
        </div>
      </div>

      <!-- 文案版本 -->
      <div v-else-if="result.type === 'copy'" class="copy-list">
        <div v-for="(v, i) in r.versions || []" :key="i" class="copy-item">
          <span class="tag">{{ v.tag }}</span>
          <span class="txt">{{ v.text }}</span>
        </div>
      </div>

      <!-- 通用提示 -->
      <div v-else class="note-box">
        <span class="note-ico">ⓘ</span>
        <span>{{ r.note || JSON.stringify(r) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  result: { type: Object, required: true },
})

const r = computed(() => props.result.data || {})
</script>
