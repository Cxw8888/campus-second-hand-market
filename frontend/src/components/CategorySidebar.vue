<script setup>
/**
 * 左侧分类侧边栏
 *
 * 图标按「分类 id → 组件对象」显式映射：只 import 用到的 7 个图标，
 * 既能被 tree-shaking，又不需要在 main.js 里全量注册 Element Plus 图标。
 */
import { Menu as IconAll, Notebook, Iphone, House, Bicycle, ShoppingBag, Box } from '@element-plus/icons-vue'
import { CATEGORIES } from '@/utils/constants'

defineProps({
  /** 当前选中的分类 id；null 表示「全部」 */
  modelValue: { type: [Number, String], default: null },
  /** 各分类的商品数（可选，键为分类 id） */
  counts: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue'])

const ICON_MAP = {
  1: Notebook,
  2: Iphone,
  3: House,
  4: Bicycle,
  5: ShoppingBag,
  6: Box
}

/** 拼出「全部 + 6 个分类」的完整列表 */
const items = [
  { id: null, name: '全部商品', icon: IconAll },
  ...CATEGORIES.map((cat) => ({ ...cat, icon: ICON_MAP[cat.id] || Box }))
]

function select(id) {
  emit('update:modelValue', id)
}
</script>

<template>
  <nav class="category-sidebar" aria-label="商品分类">
    <h2 class="category-sidebar__heading">商品分类</h2>

    <ul class="category-sidebar__list">
      <li
        v-for="item in items"
        :key="String(item.id)"
        class="category-sidebar__item"
        :class="{ 'is-active': modelValue === item.id }"
        @click="select(item.id)"
      >
        <el-icon class="category-sidebar__icon" :size="16">
          <component :is="item.icon" />
        </el-icon>
        <span class="category-sidebar__name">{{ item.name }}</span>
        <span v-if="counts[item.id] != null" class="category-sidebar__count">{{ counts[item.id] }}</span>
      </li>
    </ul>
  </nav>
</template>

<style scoped lang="scss">
.category-sidebar {
  @include cm-card;
  padding: 8px 0 12px;
  width: $cm-sidebar-width;
  flex: none;
  align-self: flex-start;
  // 跟随滚动：滚动商品列表时分类一直可见
  position: sticky;
  top: calc(#{$cm-header-height} + 16px);

  &__heading {
    font-size: 13px;
    font-weight: 600;
    color: $cm-text-secondary;
    letter-spacing: 1px;
    padding: 10px 18px 8px;
  }

  &__list {
    display: flex;
    flex-direction: column;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 42px;
    padding: 0 16px 0 14px;
    cursor: pointer;
    color: $cm-text-secondary;
    // 左侧预留的高亮竖条：用 border 而不是伪元素，避免 hover 时布局跳动
    border-left: 3px solid transparent;
    transition:
      background 0.18s ease,
      color 0.18s ease;

    &:hover {
      background: $cm-hover-bg;
      color: $cm-text;
    }

    &.is-active {
      background: $cm-primary-50;
      border-left-color: $cm-primary;
      color: $cm-primary-700;
      font-weight: 600;

      .category-sidebar__icon {
        color: $cm-primary;
      }
    }
  }

  &__icon {
    flex: none;
  }

  &__name {
    flex: 1;
    font-size: 14px;
    @include cm-ellipsis;
  }

  &__count {
    flex: none;
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  @include cm-max($cm-bp-md) {
    width: 100%;
    position: static;

    &__list {
      flex-direction: row;
      flex-wrap: wrap;
      gap: 6px;
      padding: 0 12px;
    }

    &__item {
      border-left: none;
      border-radius: $cm-radius-pill;
      height: 34px;
      padding: 0 12px;
      background: $cm-bg;

      &.is-active {
        background: $cm-primary-100;
      }
    }

    &__count {
      display: none;
    }
  }
}
</style>
