<script setup>
/**
 * 待支付倒计时
 *
 * 逻辑：剩余时间 = 下单时间 + PAY_TIMEOUT_MINUTES 分钟 − 现在。
 * 归零时 emit('expire')，由父组件去重新拉一次订单状态
 * （后端 ScheduledTasks 每分钟扫一次把超时订单置为 4-已取消，所以刷新后通常能看到「已取消」）。
 *
 * 注意：这里只是前端的展示镜像，真正的取消动作永远由后端定时任务执行 —— 前端不做任何状态推进。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PAY_TIMEOUT_MINUTES } from '@/utils/constants'

const props = defineProps({
  /** 下单时间，格式 "yyyy-MM-dd HH:mm:ss" */
  createTime: { type: String, default: '' },
  /** 超时分钟数，默认取后端配置的 15 */
  minutes: { type: Number, default: PAY_TIMEOUT_MINUTES },
  /** 紧凑模式（只显示 mm:ss，用于列表/详情头部） */
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['expire'])

const remainSeconds = ref(0)
let timer = null

/**
 * 解析后端时间字符串。
 * "2026-09-16 13:06:27" 在部分浏览器（Safari）里 new Date 会失败，
 * 所以把 '-' 换成 '/' —— 这个技巧在 utils/format.js 里也用了，保持一致。
 */
function parseTime(value) {
  if (!value) return NaN
  return new Date(String(value).replace(/-/g, '/')).getTime()
}

function computeRemain() {
  const created = parseTime(props.createTime)
  if (!Number.isFinite(created)) {
    remainSeconds.value = 0
    return
  }
  const deadline = created + props.minutes * 60 * 1000
  remainSeconds.value = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startTimer() {
  stopTimer()
  computeRemain()
  if (remainSeconds.value <= 0) {
    emit('expire')
    return
  }
  timer = setInterval(() => {
    remainSeconds.value -= 1
    if (remainSeconds.value <= 0) {
      remainSeconds.value = 0
      stopTimer()
      emit('expire')
    }
  }, 1000)
}

/** mm:ss（超过 1 小时也不会出现，15 分钟封顶） */
const formatted = computed(() => {
  const total = remainSeconds.value
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

const isUrgent = computed(() => remainSeconds.value > 0 && remainSeconds.value <= 60)

watch(() => [props.createTime, props.minutes], startTimer)
onMounted(startTimer)
onBeforeUnmount(stopTimer)
</script>

<template>
  <span class="pay-countdown" :class="{ 'is-urgent': isUrgent, 'is-over': remainSeconds <= 0 }">
    <template v-if="remainSeconds > 0">
      <template v-if="!compact">剩余 </template>
      <b class="pay-countdown__time cm-num">{{ formatted }}</b>
      <template v-if="!compact"> 未支付将自动取消</template>
    </template>
    <template v-else>已超过支付时限</template>
  </span>
</template>

<style scoped lang="scss">
.pay-countdown {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: $cm-accent-dark;

  &__time {
    font-size: 15px;
    font-weight: 800;
    letter-spacing: 1px;
  }

  // 最后 60 秒：变红并轻微呼吸，提示紧迫
  &.is-urgent {
    color: $cm-danger;

    .pay-countdown__time {
      animation: cm-pulse 1s ease-in-out infinite;
    }
  }

  &.is-over {
    color: $cm-text-secondary;
  }
}

@keyframes cm-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.45;
  }
}
</style>
