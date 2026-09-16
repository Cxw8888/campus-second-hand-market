<script setup>
/**
 * 订单时间线（下单 → 支付 → 发货 → 完成）
 *
 * 节点是「按订单实际形态算出来」的，不是写死的四条：
 *   · 面交单（trade_type=1）不经过发货环节，直接不渲染「卖家发货」节点
 *   · 已取消（4）走「下单 → 取消」，并显示取消原因与操作人
 *   · 已冻结（5）走「下单 → (支付) → 冻结」，OrderVO 没有 freezeTime 字段，所以用文字说明代替时间
 *
 * 时间字段全部来自订单自己的快照（payTime/shipTime/finishTime/cancelTime），
 * 空值即代表该环节尚未发生 —— 用空心点 + 灰色表示「未完成」。
 */
import { computed } from 'vue'
import { formatDate } from '@/utils/format'

const props = defineProps({
  /** 后端 OrderVO */
  order: { type: Object, required: true }
})

/** 取消操作人展示（cancelBy=0 代表系统自动取消） */
function cancelByText(order) {
  const by = order.cancelBy
  if (by == null || Number(by) === 0) return '系统自动取消'
  const isBuyer = String(by) === String(order.userId)
  return isBuyer ? '买家取消' : '卖家取消'
}

const steps = computed(() => {
  const o = props.order || {}
  const status = Number(o.status)
  const isFace = Number(o.tradeType) === 1
  const list = []

  list.push({
    key: 'created',
    label: '提交订单',
    time: o.createTime,
    desc: isFace ? '买家下单成功，等待约定面交' : '买家下单成功，等待支付'
  })

  // 已取消：只保留「下单 → 取消」两段，避免出现一堆永远不会发生的空节点
  if (status === 4) {
    list.push({
      key: 'cancelled',
      label: '订单已取消',
      time: o.cancelTime,
      desc: [cancelByText(o), o.cancelReason].filter(Boolean).join(' · ')
    })
    return list
  }

  list.push({
    key: 'paid',
    label: '完成支付',
    time: o.payTime,
    desc: isFace ? '面交订单可跳过支付，由卖家确认完成' : '模拟支付成功，等待卖家发货'
  })

  if (!isFace) {
    list.push({
      key: 'shipped',
      label: '卖家发货',
      time: o.shipTime,
      desc: '卖家已寄出商品'
    })
  }

  // 已冻结：走「冻结」终态，不再渲染完成节点
  if (status === 5) {
    list.push({
      key: 'frozen',
      label: '订单已冻结',
      time: null,
      desc: '因账号异常被管理员冻结，冻结即视为交易终止，库存已回补'
    })
    return list
  }

  list.push({
    key: 'finished',
    label: '交易完成',
    time: o.finishTime,
    desc: isFace ? '卖家确认面交完成' : '买家确认收货'
  })

  return list
})

/** 有时间的节点算「已发生」，用实心绿点；没时间的用空心灰点 */
function isDone(step) {
  return Boolean(step.time)
}

function timeText(step) {
  return step.time ? formatDate(step.time) : '—'
}
</script>

<template>
  <section class="order-timeline">
    <h2 class="order-timeline__title">订单进度</h2>

    <el-timeline class="order-timeline__list">
      <el-timeline-item
        v-for="step in steps"
        :key="step.key"
        :color="isDone(step) ? '#10B981' : '#D1D5DB'"
        :hollow="!isDone(step)"
        :timestamp="timeText(step)"
        placement="top"
        size="large"
      >
        <div class="order-timeline__node" :class="{ 'is-pending': !isDone(step) }">
          <span class="order-timeline__label">{{ step.label }}</span>
          <span v-if="step.desc" class="order-timeline__desc">{{ step.desc }}</span>
        </div>
      </el-timeline-item>
    </el-timeline>
  </section>
</template>

<style scoped lang="scss">
.order-timeline {
  @include cm-card;
  padding: 20px 22px 4px;

  &__title {
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 18px;
    padding-left: 10px;
    border-left: 3px solid $cm-primary;
    line-height: 1.2;
  }

  &__list {
    padding-left: 4px;

    // 时间戳和内容并排太挤，改成上下排布更清爽
    :deep(.el-timeline-item__timestamp) {
      font-size: 12px;
      color: $cm-text-placeholder;
      margin-bottom: 2px;
    }

    :deep(.el-timeline-item) {
      padding-bottom: 20px;
    }
  }

  &__node {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  &__label {
    font-size: 14px;
    font-weight: 600;
    color: $cm-text;
  }

  &__desc {
    font-size: 12px;
    color: $cm-text-secondary;
    line-height: 1.6;
  }

  // 未发生的节点整体降级为次要信息
  &__node.is-pending {
    .order-timeline__label {
      color: $cm-text-placeholder;
      font-weight: 500;
    }
  }
}
</style>
