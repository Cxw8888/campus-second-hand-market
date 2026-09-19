/**
 * PayCountdown：待支付窗口按订单 trade_type 分档（批次 6.0.7）
 *
 * 修前组件只有 minutes 一个 prop，默认 15 分钟，两个调用点（订单成功页 / 订单详情页）
 * 都没传参 ⇒ 面交单（后端窗口 120 分钟）也在 15 分钟时归零、emit('expire')，
 * 父组件据此提示"已被系统自动取消"，而后端根本没取消。
 *
 * 这里用「假定时器 + 固定 now」把时间冻住，断言的是**起始显示值**：
 *   tradeType=1 → 从 120:00 开始；tradeType=2/3 → 从 15:00 开始。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PayCountdown from '@/components/PayCountdown.vue'

/** 固定"现在"：2026-09-19 12:00:00（本地时区，与组件的 new Date('yyyy/MM/dd ...') 一致） */
const NOW = new Date('2026/09/19 12:00:00').getTime()
/** 下单时间 = 现在（剩余时间正好等于整个窗口） */
const CREATE_TIME = '2026-09-19 12:00:00'
/** 下单时间 = 20 分钟前（用于验证面交单不会在 15 分钟就到期） */
const CREATE_TIME_20MIN_AGO = '2026-09-19 11:40:00'

function mountCountdown(props) {
  return mount(PayCountdown, {
    props: { createTime: CREATE_TIME, ...props }
  })
}

const textOf = (wrapper) => wrapper.text()

describe('PayCountdown · trade_type 分档', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('① tradeType=1（面交）→ 倒计时从 120:00 开始（120 分钟窗口）', () => {
    const wrapper = mountCountdown({ tradeType: 1 })

    expect(textOf(wrapper)).toContain('120:00')
    expect(wrapper.classes()).not.toContain('is-over')
    wrapper.unmount()
  })

  it('② tradeType=2（邮寄）→ 倒计时从 15:00 开始', () => {
    const wrapper = mountCountdown({ tradeType: 2 })

    expect(textOf(wrapper)).toContain('15:00')
    wrapper.unmount()
  })

  it('③ tradeType=3（皆可）→ 与邮寄同档，从 15:00 开始', () => {
    const wrapper = mountCountdown({ tradeType: 3 })

    expect(textOf(wrapper)).toContain('15:00')
    wrapper.unmount()
  })

  it('④ 字符串形式的 tradeType 同样生效（后端 JSON 里 Long/Integer 可能是字符串）', () => {
    const wrapper = mountCountdown({ tradeType: '1' })

    expect(textOf(wrapper)).toContain('120:00')
    wrapper.unmount()
  })

  it('⑤ ★回归：下单 20 分钟后的面交单**不算超时**（修前会显示"已超过支付时限"并 emit expire）', () => {
    const wrapper = mountCountdown({ tradeType: 1, createTime: CREATE_TIME_20MIN_AGO })

    // 120 分钟窗口 → 还剩 100 分钟
    expect(textOf(wrapper)).toContain('100:00')
    expect(wrapper.classes()).not.toContain('is-over')
    expect(wrapper.emitted('expire')).toBeUndefined()
    wrapper.unmount()
  })

  it('⑥ 下单 20 分钟后的邮寄单**确实已超时**（同一条规则的另一半，防止把窗口改坏）', () => {
    const wrapper = mountCountdown({ tradeType: 2, createTime: CREATE_TIME_20MIN_AGO })

    expect(textOf(wrapper)).toContain('已超过支付时限')
    expect(wrapper.emitted('expire')).toHaveLength(1) // 挂载即已超时 → 通知一次让父组件拉真实状态
    wrapper.unmount()
  })

  it('⑦ 显式 minutes 优先于 tradeType（保留手动指定窗口的能力）', () => {
    const wrapper = mountCountdown({ tradeType: 1, minutes: 30 })

    expect(textOf(wrapper)).toContain('30:00')
    wrapper.unmount()
  })

  it('⑧ 不传 tradeType 也不传 minutes → 按邮寄档 15:00 兜底（与后端默认窗口一致）', () => {
    const wrapper = mountCountdown({})

    expect(textOf(wrapper)).toContain('15:00')
    wrapper.unmount()
  })

  it('⑨ tradeType 变化时窗口跟着变（例如详情页刷新拿到快照后重算）', async () => {
    const wrapper = mountCountdown({ tradeType: 2 })
    expect(textOf(wrapper)).toContain('15:00')

    await wrapper.setProps({ tradeType: 1 })

    expect(textOf(wrapper)).toContain('120:00')
    wrapper.unmount()
  })

  it('⑩ 最后一分钟进入紧迫态（is-urgent），与窗口长度无关', async () => {
    // 面交窗口 120 分钟，下单时间是 119 分钟前 → 只剩 60 秒
    const wrapper = mountCountdown({ tradeType: 1, createTime: '2026-09-19 10:01:00' })

    expect(wrapper.classes()).toContain('is-urgent')
    wrapper.unmount()
  })
})
