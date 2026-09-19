/**
 * 待支付超时窗口常量与分档函数（批次 6.0.7）
 *
 * 背景：后端 6.0.6（Minor 3）把超时取消窗口按**订单快照的 trade_type** 分档 ——
 *   trade_type = 1（仅面交）        → app.task.timeout-cancel.face-minutes = 120 分钟
 *   trade_type IN (2,3)（邮寄/皆可）→ app.task.timeout-cancel.minutes      = 15 分钟
 * 而前端当时仍写死 15 分钟：面交单在第 15 分钟就显示"已超过支付时限"并提示已被取消，
 * 后端却完全没有取消（它的窗口是 2 小时）——用户会以为订单没了。
 *
 * 这组用例守住的是「分档映射」本身：1 → 120，2/3 → 15，未知 → 15 兜底。
 */
import { describe, it, expect } from 'vitest'
import {
  PAY_TIMEOUT_FACE_MINUTES,
  PAY_TIMEOUT_MAIL_MINUTES,
  PAY_TIMEOUT_MINUTES,
  ORDER_STATUS_MAP,
  orderStatusHint,
  payTimeoutExpiredHint,
  payTimeoutHint,
  payTimeoutMinutes
} from '@/utils/constants'

describe('payTimeoutMinutes · 按 trade_type 分档', () => {
  it('① tradeType=1（仅面交）→ 120 分钟', () => {
    expect(payTimeoutMinutes(1)).toBe(120)
    expect(payTimeoutMinutes(1)).toBe(PAY_TIMEOUT_FACE_MINUTES)
  })

  it('② tradeType=2（仅邮寄）→ 15 分钟', () => {
    expect(payTimeoutMinutes(2)).toBe(15)
    expect(payTimeoutMinutes(2)).toBe(PAY_TIMEOUT_MAIL_MINUTES)
  })

  it('③ tradeType=3（面交/邮寄皆可）→ 15 分钟（与后端 trade_type IN (2,3) 一致）', () => {
    expect(payTimeoutMinutes(3)).toBe(PAY_TIMEOUT_MAIL_MINUTES)
  })

  it('④ 字符串形式同样生效（后端 Long 经 Jackson 序列化后是字符串）', () => {
    expect(payTimeoutMinutes('1')).toBe(120)
    expect(payTimeoutMinutes('2')).toBe(15)
    expect(payTimeoutMinutes('3')).toBe(15)
  })

  it('⑤ 未知 / 未提供 → 按邮寄档 15 分钟兜底（绝不返回 undefined 导致倒计时算成 NaN）', () => {
    expect(payTimeoutMinutes(undefined)).toBe(15)
    expect(payTimeoutMinutes(null)).toBe(15)
    expect(payTimeoutMinutes('')).toBe(15)
    expect(payTimeoutMinutes(0)).toBe(15)
    expect(payTimeoutMinutes('abc')).toBe(15)
  })

  it('⑥ 兼容常量 PAY_TIMEOUT_MINUTES 仍等于邮寄档（不破坏既有引用）', () => {
    expect(PAY_TIMEOUT_MINUTES).toBe(PAY_TIMEOUT_MAIL_MINUTES)
  })
})

describe('payTimeoutHint · 用户可见文案', () => {
  it('⑦ 面交单提示 120 分钟、邮寄单提示 15 分钟', () => {
    expect(payTimeoutHint(1)).toBe('请在 120 分钟内完成支付，超时将自动取消')
    expect(payTimeoutHint(2)).toBe('请在 15 分钟内完成支付，超时将自动取消')
    expect(payTimeoutHint(3)).toBe('请在 15 分钟内完成支付，超时将自动取消')
  })

  it('⑧ 已超时文案同样分档', () => {
    expect(payTimeoutExpiredHint(1)).toBe('超过 120 分钟未支付')
    expect(payTimeoutExpiredHint(2)).toBe('超过 15 分钟未支付')
  })
})

describe('orderStatusHint · 待支付提示随 trade_type 变化', () => {
  it('⑨ 待支付 + 面交 → 120 分钟；待支付 + 邮寄/皆可 → 15 分钟', () => {
    expect(orderStatusHint(0, 1)).toBe('请在 120 分钟内完成支付，超时将自动取消')
    expect(orderStatusHint(0, 2)).toBe('请在 15 分钟内完成支付，超时将自动取消')
    expect(orderStatusHint(0, 3)).toBe('请在 15 分钟内完成支付，超时将自动取消')
    expect(orderStatusHint('0', '1')).toBe('请在 120 分钟内完成支付，超时将自动取消')
  })

  it('⑩ 不给 tradeType 时退回通用文案（不含分钟数，避免显示错误数字）', () => {
    const hint = orderStatusHint(0)
    expect(hint).toBe(ORDER_STATUS_MAP[0].actionHint)
    expect(hint).not.toMatch(/\d+\s*分钟/)
  })

  it('⑪ 非待支付状态不受 tradeType 影响（文案仍来自状态字典）', () => {
    expect(orderStatusHint(1, 1)).toBe(ORDER_STATUS_MAP[1].actionHint)
    expect(orderStatusHint(3, 1)).toBe(ORDER_STATUS_MAP[3].actionHint)
    expect(orderStatusHint(4, 2)).toBe(ORDER_STATUS_MAP[4].actionHint)
  })

  it('⑫ 未知状态 → 空串（不抛错）', () => {
    expect(orderStatusHint(99, 1)).toBe('')
    expect(orderStatusHint(undefined, 1)).toBe('')
  })
})
