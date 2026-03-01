# 比特币后端服务

基于Spring Boot 4.x + BitcoinJ 0.17 + PostgreSQL的比特币后端服务，实现了完整的比特币资产管理功能。

## 功能特性

### 🔐 地址管理
- HD钱包地址派生（BIP32/BIP44标准）
- 根私钥安全加密存储
- 多种地址类型支持：
  - 用户充值地址
  - 热钱包地址
  - 内部查看地址

### 📊 区块扫描
- 定时区块同步
- 自动充值检测
- UTXO管理
- 区块确认跟踪
- 幂等性保障

### 💰 资产管理
- 充值处理（6确认安全机制）
- 提币申请与审核
- **批量提币处理（显著节省手续费）**
- **智能手续费优化（动态费率计算）**
- UTXO锁定防双花
- 余额实时更新

### ⚙️ 系统特性
- 定时任务调度
- 事务安全保障
- 异常恢复机制
- 日志监控

## 技术栈

- **框架**: Spring Boot 4.0.0
- **区块链**: BitcoinJ 0.17
- **数据库**: PostgreSQL 42.6.0
- **ORM**: Spring Data JPA
- **Java版本**: JDK 17

## 快速开始

### 1. 环境准备

确保安装以下软件：
- JDK 17+
- Maven 3.8+
- PostgreSQL数据库

### 2. 数据库配置

在PostgreSQL中创建数据库：
```sql
CREATE DATABASE testdb;
CREATE USER test WITH PASSWORD '123456';
GRANT ALL PRIVILEGES ON DATABASE testdb TO test;
```

### 3. 项目配置

修改 `src/main/resources/application.yml` 中的数据库连接信息：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://192.168.1.217:5432/testdb
    username: test
    password: 123456
```

### 4. 编译运行

```bash
# 编译项目
mvn clean package

# 运行应用
mvn spring-boot:run

# 或者运行打包后的jar
java -jar target/lingma-btc-demo-1.0.0.jar
```

## API接口

### 地址管理
```
POST /api/btc/initialize              # 初始化根地址
POST /api/btc/address/user/{userId}   # 生成用户充值地址
POST /api/btc/address/hot-wallet      # 生成热钱包地址
```

### 提币管理
```
POST /api/btc/withdrawal              # 提交提币申请
POST /api/btc/withdrawal/{id}/approve # 审核通过提币
POST /api/btc/withdrawal/{id}/reject  # 拒绝提币申请
```

### 系统管理
```
POST /api/btc/scan/blocks             # 手动触发区块扫描
```

## 定时任务

系统包含以下自动执行的定时任务：

| 任务 | 执行频率 | 功能 |
|------|----------|------|
| 区块扫描 | 每30秒 | 同步新区块数据 |
| 确认更新 | 每分钟 | 更新充值确认数 |
| 提币处理 | 每5分钟 | 处理已审核提币 |
| UTXO清理 | 每10分钟 | 清理过期锁定 |
| 资金归集 | 每小时 | 内部资金归集 |

## 批量提币优势

系统采用智能批量提币策略，相比传统逐笔提币可显著节省手续费：

### 💰 费用节省
- **单地址聚合**：相同目标地址的多笔提币合并为一笔交易
- **UTXO优化**：智能选择输入，最小化交易大小
- **批量签名**：一次签名处理多笔转账

### 📊 效率提升
```
传统方式：10笔提币 = 10笔交易 × 手续费
批量方式：10笔提币 = 1笔交易 × 手续费
节省：90%的交易手续费
```

### 🛡️ 安全保障
- 保持原有的UTXO锁定机制
- 支持失败回滚
- 维持交易原子性

## 智能手续费优化

系统实现动态手续费计算，根据网络状况自动调整费率：

### 🔄 动态费率机制
- **实时费率获取**：从mempool.space等API获取当前网络费率
- **时段优化**：根据不同时段调整费率策略
- **费率限制**：设置最大最小费率边界，防止极端情况

### 📈 费用估算示例
```
小交易(1输入2输出)：约226字节 × 60 sat/byte = 13,560 satoshi
中等交易(3输入4输出)：约590字节 × 60 sat/byte = 35,400 satoshi
大交易(10输入12输出)：约1898字节 × 60 sat/byte = 113,880 satoshi
```

### ⚡ 优化收益
- **精准计费**：根据实际交易复杂度计算费用
- **时段策略**：高峰期适当提高费率确保确认速度
- **成本控制**：低峰期降低费率节省成本

### 私钥保护
- 根私钥AES加密存储
- 内存中解密使用
- 支持硬件安全模块扩展

### 防双花机制
- UTXO预锁定机制
- 悲观锁防止并发
- 锁定超时自动释放

### 幂等性保障
- 交易唯一标识检查
- 重复提交防护
- 状态机控制流程

## 数据模型

### 核心实体
- `BtcAddress`: 比特币地址信息
- `BtcUtxo`: UTXO记录
- `BtcDeposit`: 充值记录
- `BtcWithdrawal`: 提币记录
- `BtcBlockScanRecord`: 区块扫描记录

### 状态流转
```
充值: PENDING → CONFIRMED → COMPLETED
提币: PENDING → APPROVED → PROCESSING → COMPLETED/FAILED
UTXO: UNSPENT → LOCKED → SPENT
```

## 监控告警

系统提供以下监控指标：
- 热钱包余额监控
- 任务执行延迟监控
- 失败交易告警
- 区块同步状态

## 注意事项

⚠️ **重要提醒**：
1. 生产环境请务必修改默认密码
2. 根私钥密码应从安全存储获取
3. 建议启用硬件安全模块
4. 定期备份数据库
5. 监控系统运行状态

## 开发指南

### 代码结构
```
src/main/java/com/btc/
├── config/          # 配置类
├── controller/      # REST控制器
├── entity/          # JPA实体
├── repository/      # 数据访问层
├── scheduler/       # 定时任务
├── service/         # 业务逻辑层
└── Application.java # 启动类
```

### 扩展建议
- 集成真实的区块链API
- 添加更完善的监控面板
- 实现多签钱包功能
- 增加冷钱包支持
- 添加风控策略

## 许可证

MIT License