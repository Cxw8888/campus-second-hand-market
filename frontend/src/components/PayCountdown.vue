<script setup>
/**
 * 待支付倒计时
 *
 * 逻辑：剩余时间 = 下单时间 + 窗口分钟数 − 现在。
 * 归零时 emit('expire')，由父组件去重新拉一次订单状态
 * （后端 ScheduledTasks 每分钟扫一次把超时订单置为 4-已取消，所以刷新后通常能看到「已取消」）。
 *
 * 窗口分钟数**按订单快照的 trade_type 分档**（批次 6.0.7 起）：
 *   tradeType=1（仅面交）        → 120 分钟（后端 app.task.timeout-cancel.face-minutes）
 *   tradeType=2/3（邮寄/皆可）   → 15 分钟（后端 app.task.timeout-cancel.minutes）
 * 修前这里写死 15 分钟，面交单会在第 15 分钟显示「已超过支付时限」并让父组件去刷新，
 * 而后端此时并没有取消（面交窗口是 2 小时）——用户会以为订单被取消了。
 *
 * 注意：这里只是前端的展示镜像，真正的取消动作永远由后端定时任务执行 —— 前端不做任何状态推进。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { payTimeoutMinutes } from '@/utils/constants'

const props = defineProps({
  /** 下单时间，格式 "yyyy-MM-dd HH:mm:ss" */
  createTime: { type: String, default: '' },
  /**
   * 订单快照的 trade_type：1=仅面交（120 分钟），2=仅邮寄 / 3=皆可（15 分钟）。
   * 未提供时按邮寄档 15 分钟兜底（与后端 IN (2,3) 的默认窗口一致）。
   */
  tradeType: { type: [Number, String], default: null },
  /** 显式指定窗口分钟数（优先于 tradeType，仅用于测试/特殊场景） */
  minutes: { type: Number, default: null },
  /** 紧凑模式（只显示 mm:ss，用于列表/详情头部） */
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['expire'])

/**
 * 本次倒计时的窗口（分钟）。
 * 优先级：显式 minutes > 按 tradeType 分档 > 邮寄默认 15 分钟。
 */
const windowMinutes = computed(() => {
  if (props.minutes !== null && props.minutes !== undefined) {
    return props.minutes
  }
  return payTimeoutMinutes(props.tradeType)
})

const remainSeconds = ref(0)
let timer = null
/**
 * 已经为「哪一组 (createTime, windowMinutes)」通知过父组件。
 *
 * ⚠️ 必须去重：倒计时归零时组件自己并不知道后端有没有真的把订单改成 4-已取消，
 *    父组件收到 expire 后一般会重新拉一次订单详情。如果父组件那次刷新又把本组件卸载重建
 *    （例如刷新时切回了骨架屏），重建后 onMounted → 依然是"已超时" → 立刻再 emit 一次
 *    → 父组件再刷新 …… 形成「刷新 ↔ 重建」死循环，页面永远卡在骨架屏、请求也会被打爆。
 *    所以同一笔订单只通知一次；订单本身换了（createTime / 窗口变了）才允许再次通知。
 */
let notifiedKey = ''

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
  const deadline = created + windowMinutes.value * 60 * 1000
  remainSeconds.value = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
}

/**
 * 首帧就算一次剩余时间。
 *
 * 只在 onMounted 里算是**不够**的：Vue 的首帧渲染发生在 onMounted 之前，
 * 而 onMounted 里的赋值要等到下一个微任务才反映到 DOM —— 首帧会闪一下
 * 「已超过支付时限」（浏览器一般在同一帧内就重绘完了，肉眼看不出，但测试能看见，
 * 而且页面真慢的时候用户可能看到）。这里在 setup 阶段先算一次，首帧就是正确的剩余时间。
 */
computeRemain()

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 同一笔订单只通知一次，见 notifiedKey 的说明 */
function emitExpireOnce() {
  const key = `${props.createTime}|${windowMinutes.value}`
  if (notifiedKey === key) return
  notifiedKey = key
  emit('expire')
}

function startTimer() {
  stopTimer()
  computeRemain()
  if (remainSeconds.value <= 0) {
    // 挂载时就已经超时（比如用户几分钟后才打开详情页）：通知一次让父组件拉最新状态
    emitExpireOnce()
    return
  }
  timer = setInterval(() => {
    remainSeconds.value -= 1
    if (remainSeconds.value <= 0) {
      remainSeconds.value = 0
      stopTimer()
      emitExpireOnce()
    }
  }, 1000)
}

/**
 * mm:ss。
 *
 * 面交单的窗口是 120 分钟，所以**分钟位可能是 3 位数**（"120:00"）——
 * 这里按位数补齐（minWidth=2），不要假设"最多 2 位分钟"。
 */
const formatted = computed(() => {
  const total = remainSeconds.value
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

const isUrgent = computed(() => remainSeconds.value > 0 && remainSeconds.value <= 60)

/**
 * 用「getter 数组」而不是 `() => [a, b]`：
 * 后者每次求值都返回**新数组**，Vue 判等永远认为变了，任何一次重新求值都会触发回调，
 * 在这里就意味着倒计时被反复重启（进而反复 emit）。数组里放 getter，只有值真的变了才触发。
 */
watch([() => props.createTime, () => props.minutes, () => props.tradeType], startTimer)
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
