<script setup>
/**
 * 站内信列表项
 *
 * 类型图标（已核对 Element Plus 实际导出，见 constants.js 的说明）：
 *   type=1 订单通知 → ShoppingBag（购物袋）
 *   type=2 审核通知 → Stamp（印章）
 *   type=3 系统通知 → Bell（铃铛）
 *
 * ⚠️ 需求原话是「购物袋 / 盾牌 / 喇叭」，但 **EP 没有 Shield/ShieldCheck，也没有喇叭图标**，
 *    所以后两个按语义等价替换：审核用「印章」（贴合中文"审核盖章"），系统通知用「铃铛」
 *    （通用通知语义，且与导航栏消息中心图标一致）。替换理由已写进 constants.js。
 *
 * 组件本身只负责展示与派发点击，不做接口调用 —— 标记已读与跳转由页面统一处理，
 * 避免「组件里偷偷发请求」造成状态不同步。
 */
import { computed } from 'vue'
import { ShoppingBag, Stamp, Bell, ArrowRight } from '@element-plus/icons-vue'
import { formatRelativeTime } from '@/utils/format'
import {
  notificationIconName,
  notificationTone,
  notificationTypeLabel,
  resolveNotificationTarget
} from '@/utils/constants'

const props = defineProps({
  /** 后端 NotificationVO */
  item: { type: Object, required: true }
})

const emit = defineEmits(['open'])

const ICON_MAP = { ShoppingBag, Stamp, Bell }

const iconComponent = computed(() => ICON_MAP[notificationIconName(props.item.type)] || Bell)
const tone = computed(() => notificationTone(props.item.type))
const typeLabel = computed(() => notificationTypeLabel(props.item.type))
const timeText = computed(() => formatRelativeTime(props.item.createTime))
const unread = computed(() => Number(props.item.isRead) === 0)

/** 有跳转目标时给一个明确的行动提示（消息中心是待办入口，不是纯展示） */
const target = computed(() => resolveNotificationTarget(props.item))
const actionText = computed(() => {
  if (!target.value) return ''
  return target.value.name === 'order-detail' ? '查看订单' : '查看商品'
})

function handleClick() {
  emit('open', props.item)
}
</script>

<template>
  <article class="notice-item" :class="{ 'is-unread': unread }" @click="handleClick">
    <!-- 未读圆点：最直观的未读信号，配合右侧加粗一起用 -->
    <span class="notice-item__dot" :class="{ 'is-on': unread }" aria-hidden="true" />

    <span class="notice-item__icon" :class="`is-${tone}`">
      <el-icon :size="17"><component :is="iconComponent" /></el-icon>
    </span>

    <div class="notice-item__main">
      <div class="notice-item__head">
        <span class="notice-item__type">{{ typeLabel }}</span>
        <span class="notice-item__time">{{ timeText }}</span>
      </div>
      <p class="notice-item__content">{{ item.content }}</p>
    </div>

    <span v-if="actionText" class="notice-item__action">
      {{ actionText }}
      <el-icon :size="12"><ArrowRight /></el-icon>
    </span>
  </article>
</template>

<style scoped lang="scss">
.notice-item {
  @include cm-card;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px 18px;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: $cm-shadow-card-hover;

    .notice-item__action {
      color: $cm-primary;
    }
  }

  // 未读：左侧留出圆点位置 + 正文加粗
  &__dot {
    flex: none;
    width: 7px;
    height: 7px;
    margin-top: 8px;
    border-radius: 50%;
    background: transparent;

    &.is-on {
      background: $cm-accent;
      box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.16);
    }
  }

  &__icon {
    @include cm-center;
    flex: none;
    width: 38px;
    height: 38px;
    border-radius: $cm-radius;
    color: $cm-primary;
    background: $cm-primary-50;

    &.is-blue {
      color: #1d4ed8;
      background: $cm-blue-50;
    }

    &.is-gray {
      color: $cm-gray-tag;
      background: $cm-gray-tag-50;
    }
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__head {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 4px;
  }

  &__type {
    font-size: 12px;
    font-weight: 600;
    color: $cm-text-secondary;
  }

  &__time {
    font-size: 11px;
    color: $cm-text-placeholder;
  }

  &__content {
    font-size: 13px;
    line-height: 1.7;
    color: $cm-text-secondary;
    // 内容可能较长，最多展示三行
    @include cm-ellipsis-lines(3);
  }

  // 未读整条加强：正文颜色与字重提升
  &.is-unread {
    .notice-item__type {
      color: $cm-primary-700;
    }

    .notice-item__content {
      color: $cm-text;
      font-weight: 500;
    }
  }

  &__action {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 2px;
    align-self: center;
    font-size: 12px;
    color: $cm-text-placeholder;
    transition: color 0.18s ease;
  }

  @include cm-max($cm-bp-sm) {
    &__action {
      display: none;
    }
  }
}
</style>
