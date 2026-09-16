/**
 * 本地兜底数据（mock）
 *
 * 使用场景（仅一种）：商品列表接口**报错**时兜底，保证答辩现场演示不空场。
 * 明确不参与的场景：
 *   · 接口返回成功但 total === 0 —— 显示的是「暂无商品，快去发布第一件吧」空状态，
 *     不能拿 mock 假装有数据（那样会把「后端真的没数据」这个事实盖掉）。
 *
 * coverImage 故意留空：后端 uploads 目录当前为空，seed 里的 demo*.jpg 并不存在，
 * 所以真实数据同样会走占位图。统一走占位图，视觉反而更整齐。
 */

/** 8 条覆盖 6 个分类的示例数据 */
const MOCK_RECORDS = [
  {
    id: 'mock-1',
    title: '高等数学（第七版）上下册 合售',
    price: 25.0,
    stock: 2,
    conditionLevel: 2,
    tradeType: 3,
    tradeLocation: '三教门口',
    coverImage: '',
    categoryId: 1,
    categoryName: '教材书籍',
    status: 1,
    sellerId: 'mock-s1',
    sellerNickname: '数院小周',
    sellerAvatar: '',
    createTime: '2026-09-15 10:20:00'
  },
  {
    id: 'mock-2',
    title: '线性代数辅导讲义（含笔记）',
    price: 12.5,
    stock: 1,
    conditionLevel: 3,
    tradeType: 1,
    tradeLocation: '图书馆一楼',
    coverImage: '',
    categoryId: 1,
    categoryName: '教材书籍',
    status: 1,
    sellerId: 'mock-s2',
    sellerNickname: '备考的猫',
    sellerAvatar: '',
    createTime: '2026-09-14 19:05:00'
  },
  {
    id: 'mock-3',
    title: 'iPad 第 9 代 64G 深空灰（带笔）',
    price: 1580.0,
    stock: 1,
    conditionLevel: 2,
    tradeType: 3,
    tradeLocation: '南门车棚',
    coverImage: '',
    categoryId: 2,
    categoryName: '数码电子',
    status: 1,
    sellerId: 'mock-s3',
    sellerNickname: '毕业清仓',
    sellerAvatar: '',
    createTime: '2026-09-14 15:42:00'
  },
  {
    id: 'mock-4',
    title: '罗技 M330 静音鼠标',
    price: 45.0,
    stock: 3,
    conditionLevel: 1,
    tradeType: 2,
    tradeLocation: '',
    coverImage: '',
    categoryId: 2,
    categoryName: '数码电子',
    status: 1,
    sellerId: 'mock-s4',
    sellerNickname: '电子爱好者',
    sellerAvatar: '',
    createTime: '2026-09-13 21:10:00'
  },
  {
    id: 'mock-5',
    title: '宿舍小台灯（三档护眼）',
    price: 18.0,
    stock: 4,
    conditionLevel: 3,
    tradeType: 1,
    tradeLocation: '四号宿舍楼下',
    coverImage: '',
    categoryId: 3,
    categoryName: '生活用品',
    status: 1,
    sellerId: 'mock-s5',
    sellerNickname: '爱整理的室友',
    sellerAvatar: '',
    createTime: '2026-09-13 09:30:00'
  },
  {
    id: 'mock-6',
    title: '九成新羽毛球拍 双拍套装',
    price: 68.0,
    stock: 1,
    conditionLevel: 2,
    tradeType: 3,
    tradeLocation: '体育馆东门',
    coverImage: '',
    categoryId: 4,
    categoryName: '运动户外',
    status: 1,
    sellerId: 'mock-s6',
    sellerNickname: '校队阿凯',
    sellerAvatar: '',
    createTime: '2026-09-12 18:00:00'
  },
  {
    id: 'mock-7',
    title: '优衣库轻薄羽绒服 M 码',
    price: 130.0,
    stock: 1,
    conditionLevel: 2,
    tradeType: 1,
    tradeLocation: '二食堂门口',
    coverImage: '',
    categoryId: 5,
    categoryName: '服饰鞋包',
    status: 1,
    sellerId: 'mock-s7',
    sellerNickname: '换季选手',
    sellerAvatar: '',
    createTime: '2026-09-12 12:15:00'
  },
  {
    id: 'mock-8',
    title: '多肉盆栽 + 陶瓷花盆（两盆）',
    price: 22.0,
    stock: 2,
    conditionLevel: 1,
    tradeType: 1,
    tradeLocation: '实验楼中庭',
    coverImage: '',
    categoryId: 6,
    categoryName: '其他闲置',
    status: 1,
    sellerId: 'mock-s8',
    sellerNickname: '绿植社',
    sellerAvatar: '',
    createTime: '2026-09-11 16:40:00'
  }
]

/**
 * 生成一页 mock 数据，形状与后端 PageResult<ProductListVO> 完全一致
 * （注意 total 等按后端约定用字符串，保证前端处理逻辑与真实数据同一条路径）
 */
export function mockProductPage() {
  return {
    total: String(MOCK_RECORDS.length),
    pages: '1',
    current: '1',
    size: String(MOCK_RECORDS.length),
    records: MOCK_RECORDS.map((item) => ({ ...item }))
  }
}

/** 详情页兜底数据：在 mock 列表项基础上补齐 description / imageUrls */
export function mockProductDetail(id) {
  const found = MOCK_RECORDS.find((item) => String(item.id) === String(id))
  const base = found || MOCK_RECORDS[0]
  return {
    ...base,
    id: base.id,
    description:
      '（本地兜底数据）商品成色良好，平时使用爱惜，功能一切正常。支持校内当面验货，' +
      '满意再交易，可小刀。有需要请直接下单或站内信联系。',
    imageUrls: []
  }
}
