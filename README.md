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

### 📊 区块链数据同步
- **多API提供者支持**：
  - **blockchain.info** - 用于主网数据获取
  - **mempool.space** - 用于测试网(testnet4)数据获取
- **自动网络切换**：根据配置自动选择对应API
- **Kafka集成**：区块数据通过Kafka消息队列分发
- **分页交易获取**：支持大量交易的区块数据同步
- **API Key认证**：支持mempool.space API Key认证

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
- 主网/测试网一键切换
- 定时任务调度
- 事务安全保障
- 异常恢复机制
- 日志监控

## 技术栈

- **框架**: Spring Boot 4.0.0
- **区块链**: BitcoinJ 0.17
- **数据库**: PostgreSQL 42.6.0
- **消息队列**: Apache Kafka
- **ORM**: Spring Data JPA
- **Java版本**: JDK 17

## 快速开始

### 1. 环境准备

确保安装以下软件：
- JDK 17+
- Maven 3.8+
- PostgreSQL数据库
- Apache Kafka（可选，用于区块数据分发）

### 2. 数据库配置

在PostgreSQL中创建数据库：
```sql
CREATE DATABASE testdb1;
CREATE USER test WITH PASSWORD '123456';
GRANT ALL PRIVILEGES ON DATABASE testdb1 TO test;
```

### 3. 项目配置

修改 `src/main/resources/application.yml` 中的配置：

```yaml
# 数据库配置
spring:
  datasource:
    url: jdbc:postgresql://192.168.1.217:5432/testdb1
    username: test
    password: 123456
  
  # Kafka配置
  kafka:
    bootstrap-servers: 192.168.1.217:9092

# 比特币网络配置
bitcoin:
  network: testnet  # mainnet 或 testnet

# 区块链API配置
blockchain:
  api:
    mempool:
      base-url: https://mempool.space
      api-key: your-api-key  # 可选，用于提高请求限制
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
GET  /api/network/status              # 获取网络状态
POST /api/network/switch              # 切换主网/测试网
```

## 区块链API集成

### API提供者

系统支持多个区块链数据提供者，根据网络配置自动选择：

| 提供者 | 网络 | API端点 | 认证方式 |
|--------|------|---------|----------|
| blockchain.info | mainnet | https://blockchain.info | 无需认证 |
| mempool.space | testnet | https://mempool.space/testnet4/api | API Key（可选） |

### mempool.space API

测试网使用mempool.space的testnet4 API：

```
# 获取最新区块高度
GET /testnet4/api/blocks/tip/height

# 获取区块哈希
GET /testnet4/api/block-height/:height

# 获取区块交易（分页）
GET /testnet4/api/block/:hash/txs
GET /testnet4/api/block/:hash/txs/:start_index
```

### API Key配置

配置mempool.space API Key可提高请求限制：

```yaml
blockchain:
  api:
    mempool:
      api-key: your-api-key
```

请求时自动添加认证头：
```
Authorization: Bearer your-api-key
```

## Kafka数据流

系统使用Kafka进行区块数据分发：

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ BlockDataFetch  │────▶│     Kafka       │────▶│ BlockDataConsumer│
│    Service      │     │  bitcoin-block  │     │    Service       │
└─────────────────┘     │     -data       │     └─────────────────┘
                        └─────────────────┘
```

### Kafka配置

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    topic:
      block-data: bitcoin-block-data
    consumer:
      group-id: btc-block-processor
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

## 主网/测试网切换

系统支持主网和测试网一键切换：

### 配置方式

```yaml
bitcoin:
  network: testnet  # mainnet 或 testnet
```

### 运行时切换

```bash
# 切换到主网
POST /api/network/switch?network=mainnet

# 切换到测试网
POST /api/network/switch?network=testnet
```

切换后系统会自动：
- 选择对应的API提供者
- 更新地址前缀
- 调整网络参数

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

## 安全机制

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
- `BtcBlockSyncRecord`: 区块同步记录（Kafka）
- `BtcSyncStatus`: 同步状态
- `BtcWalletSeed`: 钱包种子

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
- API提供者状态

## 项目结构

```
src/main/java/com/btc/
├── Application.java           # 启动类
├── client/
│   └── BitcoinRpcClient.java  # Bitcoin RPC客户端
├── config/
│   ├── JpaConfig.java         # JPA配置
│   └── KafkaConfig.java       # Kafka配置
├── controller/
│   ├── BitcoinController.java # Bitcoin REST控制器
│   └── NetworkController.java # 网络切换控制器
├── entity/                    # JPA实体
├── repository/                # 数据访问层
├── scheduler/
│   └── BitcoinScheduler.java  # 定时任务
├── service/
│   ├── AddressManagementService.java      # 地址管理
│   ├── BlockScanningService.java          # 区块扫描
│   ├── BlockDataFetchService.java         # 区块数据获取
│   ├── BlockDataKafkaProducer.java        # Kafka生产者
│   ├── BlockDataKafkaConsumer.java        # Kafka消费者
│   ├── BlockchainApiManager.java          # API管理器
│   ├── BlockchainApiProvider.java         # API提供者接口
│   ├── BlockchainInfoProvider.java        # blockchain.info实现
│   ├── MempoolSpaceProvider.java          # mempool.space实现
│   ├── NetworkSwitchService.java          # 网络切换
│   ├── TransactionSigningService.java     # 交易签名
│   ├── TransactionBroadcastService.java   # 交易广播
│   ├── WithdrawalService.java             # 提币服务
│   └── SimpleEcdsaSigningService.java     # ECDSA签名
├── wallet/
│   ├── HDWalletManager.java               # HD钱包管理
│   └── WalletInitializationService.java   # 钱包初始化
├── monitor/
│   └── BlockchainMonitorService.java      # 区块链监控
└── util/
    └── LogUtil.java                       # 日志工具
```

## 注意事项

⚠️ **重要提醒**：
1. 生产环境请务必修改默认密码
2. 根私钥密码应从安全存储获取
3. 建议启用硬件安全模块
4. 定期备份数据库
5. 监控系统运行状态
6. API Key不要提交到版本控制

## 开发指南

### 本地开发

```bash
# 使用H2内存数据库
mvn spring-boot:run -Dspring.profiles.active=h2

# 运行测试
mvn test
```

### 扩展建议
- 集成更多区块链API提供者
- 添加更完善的监控面板
- 实现多签钱包功能
- 增加冷钱包支持
- 添加风控策略

## 许可证

MIT License