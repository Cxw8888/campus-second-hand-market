-- =====================================================================
-- 校园二手交易平台 Flyway 初始化脚本
-- 版本: V1
-- 数据库: MySQL 8.0  字符集: utf8mb4 / utf8mb4_0900_ai_ci  时区: GMT+8
-- 约定:
--   1. 除 tb_favorite / shedlock / tb_audit_log 三张例外表外,
--      所有表显式包含 create_time / update_time / is_deleted。
--   2. 仅逻辑外键, 不创建物理外键。
--   3. 时间戳统一 DATETIME (shedlock 例外, 必须 TIMESTAMP(3))。
--   4. 手写自定义 SQL 必须显式携带 AND is_deleted = 0。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 2.1 用户表 tb_user
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '学号/账号',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码(无salt字段)',
    `nickname`    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255)          DEFAULT NULL COMMENT '头像URL',
    `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `email`       VARCHAR(100)          DEFAULT NULL COMMENT '校园邮箱',
    `role`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-学生, 1-管理员',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-正常, 1-封禁',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    UNIQUE KEY `uk_user_email` (`email`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 2.2 商品分类表 tb_category
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序权重',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品分类表';

-- ---------------------------------------------------------------------
-- 2.3 商品表 tb_product
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_product`
(
    `id`              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `user_id`         BIGINT         NOT NULL COMMENT '发布者ID',
    `category_id`     BIGINT         NOT NULL COMMENT '分类ID',
    `title`           VARCHAR(100)   NOT NULL COMMENT '商品标题',
    `description`     TEXT COMMENT '商品描述',
    `price`           DECIMAL(10, 2) NOT NULL COMMENT '价格',
    `stock`           INT            NOT NULL DEFAULT 1 COMMENT '库存数量',
    `condition_level` TINYINT        NOT NULL COMMENT '成色: 1-全新, 2-几乎全新, 3-轻微使用痕迹, 4-明显使用痕迹',
    `trade_type`      TINYINT        NOT NULL COMMENT '交易方式: 1-仅面交, 2-仅邮寄, 3-两者皆可',
    `trade_location`  VARCHAR(100)            DEFAULT NULL COMMENT '面交地点',
    `image_urls`      JSON                    DEFAULT NULL COMMENT '商品多图(JSON数组)',
    `status`          TINYINT        NOT NULL DEFAULT 3 COMMENT '0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`      TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_category_status_price` (`category_id`, `status`, `price`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_title` (`title`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品表';

-- ---------------------------------------------------------------------
-- 2.4 订单表 tb_order
--     状态: 0-待支付 1-已支付待发货 2-已发货待收货 3-已完成
--           4-已取消 5-已冻结 6-退款申请中 7-退款被拒
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_order`
(
    `id`                   BIGINT         NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`             VARCHAR(64)    NOT NULL COMMENT '订单号(雪花算法)',
    `user_id`              BIGINT         NOT NULL COMMENT '买家ID',
    `product_id`           BIGINT         NOT NULL COMMENT '商品ID',
    `seller_id`            BIGINT         NOT NULL COMMENT '卖家ID',
    `product_price`        DECIMAL(10, 2) NOT NULL COMMENT '价格快照',
    `amount`               DECIMAL(10, 2) NOT NULL COMMENT '总金额(= product_price × quantity)',
    `quantity`             INT            NOT NULL COMMENT '购买数量',
    `status`               TINYINT        NOT NULL DEFAULT 0 COMMENT '0-待支付,1-已支付待发货,2-已发货待收货,3-已完成,4-已取消,5-已冻结,6-退款申请中,7-退款被拒',
    `address`              VARCHAR(255)            DEFAULT NULL COMMENT '收货地址(邮寄必填)',
    `trade_type`           TINYINT        NOT NULL COMMENT '交易方式快照(后端从商品读取)',
    `pay_time`             DATETIME                DEFAULT NULL COMMENT '支付时间',
    `ship_time`            DATETIME                DEFAULT NULL COMMENT '发货时间',
    `finish_time`          DATETIME                DEFAULT NULL COMMENT '完成时间',
    `cancel_time`          DATETIME                DEFAULT NULL COMMENT '取消时间',
    `cancel_reason`        VARCHAR(200)            DEFAULT NULL COMMENT '取消原因',
    `cancel_by`            BIGINT         NOT NULL DEFAULT 0 COMMENT '取消操作人(0=系统)',
    `refund_apply_time`    DATETIME                DEFAULT NULL COMMENT '退款申请时间',
    `refund_reject_time`   DATETIME                DEFAULT NULL COMMENT '卖家拒绝退款时间',
    `product_title`        VARCHAR(100)   NOT NULL COMMENT '商品标题快照(下单时写入)',
    `refund_reason`        VARCHAR(200)            DEFAULT NULL COMMENT '买家申请退款原因',
    `refund_reject_reason` VARCHAR(200)            DEFAULT NULL COMMENT '卖家拒绝退款原因',
    `create_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`           TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_status_time` (`user_id`, `status`, `create_time`),
    KEY `idx_seller_status_time` (`seller_id`, `status`, `create_time`),
    KEY `idx_status_create_time` (`status`, `create_time`),
    KEY `idx_order_product_id` (`product_id`),
    KEY `idx_status_refund_reject_time` (`status`, `refund_reject_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单表';

-- ---------------------------------------------------------------------
-- 2.5 收藏表 tb_favorite
--     例外表: 无 is_deleted / update_time, 取消收藏为物理删除
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_favorite`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `product_id`  BIGINT   NOT NULL COMMENT '商品ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='收藏表(物理删除)';

-- ---------------------------------------------------------------------
-- 2.6 站内信通知表 tb_notification
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_notification`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id`     BIGINT       NOT NULL COMMENT '接收者ID',
    `type`        TINYINT      NOT NULL COMMENT '1-订单, 2-审核, 3-系统',
    `biz_type`    TINYINT      NOT NULL DEFAULT 0 COMMENT '业务类型: 1-订单, 2-商品, 3-系统',
    `biz_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '业务ID(系统通知=0)',
    `content`     VARCHAR(500) NOT NULL COMMENT '内容',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0-未读, 1-已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_read_time` (`user_id`, `is_read`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='站内信通知表';

-- ---------------------------------------------------------------------
-- 2.7 ShedLock 锁表 shedlock
--     第三方框架表, 豁免全局字段规则; 时间精度必须 TIMESTAMP(3)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shedlock`
(
    `name`       VARCHAR(64)  NOT NULL COMMENT '锁名称',
    `lock_until` TIMESTAMP(3) NOT NULL COMMENT '锁释放时间',
    `locked_at`  TIMESTAMP(3) NOT NULL COMMENT '加锁时间',
    `locked_by`  VARCHAR(255) NOT NULL COMMENT '实例标识',
    PRIMARY KEY (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='ShedLock 分布式锁表';

-- ---------------------------------------------------------------------
-- 2.8 管理端审计日志表 tb_audit_log
--     例外表: 只追加, 无 update_time / is_deleted
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_audit_log`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `operator_id`    BIGINT       NOT NULL COMMENT '操作人ID',
    `operator_name`  VARCHAR(50)  NOT NULL COMMENT '操作人名称',
    `operation_type` VARCHAR(50)  NOT NULL COMMENT 'APPROVE_PRODUCT/REJECT_PRODUCT/BAN_USER/UNBAN_USER/FORCE_OFFLINE/UNFREEZE_ORDER/COMPLETE_ORDER/CREATE_CATEGORY/UPDATE_CATEGORY/DELETE_CATEGORY/REFUND_ORDER',
    `target_type`    VARCHAR(50)  NOT NULL COMMENT '目标类型',
    `target_id`      BIGINT       NOT NULL COMMENT '目标ID',
    `result`         TINYINT               DEFAULT NULL COMMENT '结果: 1-成功, 0-失败',
    `detail`         VARCHAR(500)          DEFAULT NULL COMMENT '详情',
    `ip`             VARCHAR(50)           DEFAULT NULL COMMENT '操作IP',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_operator_time` (`operator_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='管理端审计日志表(只追加)';

-- ---------------------------------------------------------------------
-- 基础数据: 默认分类
-- ---------------------------------------------------------------------
INSERT INTO `tb_category` (`name`, `sort`, `create_time`, `update_time`, `is_deleted`)
VALUES ('教材书籍', 10, NOW(), NOW(), 0),
       ('数码电子', 20, NOW(), NOW(), 0),
       ('生活用品', 30, NOW(), NOW(), 0),
       ('运动户外', 40, NOW(), NOW(), 0),
       ('服饰鞋包', 50, NOW(), NOW(), 0),
       ('其他闲置', 99, NOW(), NOW(), 0);
