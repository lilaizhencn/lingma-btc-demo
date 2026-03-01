package com.btc.service;

import com.btc.entity.BtcBlockSyncRecord;
import com.btc.entity.BtcSyncStatus;
import com.btc.repository.BtcBlockSyncRecordRepository;
import com.btc.repository.BtcSyncStatusRepository;
import com.btc.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 区块数据获取服务
 * 负责下载区块数据，保存到本地文件，并发送到Kafka
 * 支持根据网络配置自动选择API提供者（blockchain.info主网，mempool.space测试网）
 */
@Service
public class BlockDataFetchService {
    
    private final BtcBlockSyncRecordRepository syncRecordRepository;
    private final BtcSyncStatusRepository syncStatusRepository;
    private final BlockDataKafkaProducer kafkaProducer;
    private final BlockchainApiManager apiManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${blockchain.data.storage-path:./blockchain-data}")
    private String dataStoragePath;
    
    @Autowired
    public BlockDataFetchService(
            BtcBlockSyncRecordRepository syncRecordRepository,
            BtcSyncStatusRepository syncStatusRepository,
            BlockDataKafkaProducer kafkaProducer,
            BlockchainApiManager apiManager) {
        this.syncRecordRepository = syncRecordRepository;
        this.syncStatusRepository = syncStatusRepository;
        this.kafkaProducer = kafkaProducer;
        this.apiManager = apiManager;
    }
    
    /**
     * 初始化数据存储目录
     * 在Spring完成属性注入后调用
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 确保数据存储目录存在
        File storageDir = new File(dataStoragePath);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        LogUtil.info(this.getClass(), "区块数据存储目录初始化完成: " + storageDir.getAbsolutePath());
        LogUtil.info(this.getClass(), "使用API提供者: " + apiManager.getCurrentProviderName() + 
                    ", 当前网络: " + apiManager.getCurrentNetwork());
    }

    
    /**
     * 执行区块数据同步
     * 定时任务调用此方法
     */
    @Transactional
    public void syncBlockData() {
        LogUtil.info(this.getClass(), "开始执行区块数据同步... [API提供者: " + apiManager.getCurrentProviderName() + "]");
        
        try {
            // 获取或创建同步状态
            BtcSyncStatus syncStatus = getOrCreateSyncStatus();
            
            // 获取最新区块高度（根据当前网络自动选择API）
            long networkHeight = apiManager.getLatestBlockHeight();
            syncStatus.setNetworkLatestHeight(networkHeight);
            
            // 获取需要同步的区块范围
            long syncedHeight = syncStatus.getSyncedBlockHeight();
            long startHeight = syncedHeight + 1;
            
            if (startHeight > networkHeight) {
                LogUtil.info(this.getClass(), "已是最新区块，无需同步。当前高度: " + syncedHeight);
                return;
            }
            
            // 每次最多同步100个区块
            long endHeight = Math.min(startHeight + 99, networkHeight);
            
            LogUtil.info(this.getClass(), String.format(
                "同步区块范围: %d - %d, 网络最新高度: %d, 网络: %s", 
                startHeight, endHeight, networkHeight, apiManager.getCurrentNetwork()));
            
            // 逐个获取区块数据
            for (long height = startHeight; height <= endHeight; height++) {
                try {
                    fetchAndProcessBlock(height);
                    syncStatus.setSyncedBlockHeight(height);
                    syncStatus.setLastSyncAt(LocalDateTime.now());
                    syncStatus.setBehindBlocks(networkHeight - height);
                } catch (Exception e) {
                    LogUtil.error(this.getClass(), "同步区块失败: " + height + ", 错误: " + e.getMessage(), e);
                    syncStatus.setSyncState(BtcSyncStatus.SyncState.ERROR);
                    syncStatus.setLastError("区块 " + height + " 同步失败: " + e.getMessage());
                    syncStatusRepository.save(syncStatus);
                    break;
                }
            }
            
            // 更新同步状态
            if (syncStatus.getSyncState() != BtcSyncStatus.SyncState.ERROR) {
                syncStatus.setSyncState(BtcSyncStatus.SyncState.RUNNING);
                syncStatus.setLastError(null);
            }
            syncStatusRepository.save(syncStatus);
            
            LogUtil.info(this.getClass(), "区块数据同步完成");
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "区块数据同步失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取并处理单个区块
     */
    private void fetchAndProcessBlock(long blockHeight) {
        // 检查是否已存在记录
        Optional<BtcBlockSyncRecord> existingRecord = syncRecordRepository.findByBlockHeight(blockHeight);
        if (existingRecord.isPresent() && existingRecord.get().getSyncStatus() == BtcBlockSyncRecord.SyncStatus.SENT) {
            LogUtil.debug(this.getClass(), "区块已同步: " + blockHeight);
            return;
        }
        
        // 创建同步记录
        BtcBlockSyncRecord record = existingRecord.orElse(new BtcBlockSyncRecord());
        record.setBlockHeight(blockHeight);
        record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.FETCHING);
        syncRecordRepository.save(record);
        
        try {
            // 通过API管理器获取区块数据（自动选择正确的API）
            String blockHash = apiManager.getBlockHash(blockHeight);
            String rawJson = apiManager.getBlockDataJson(blockHash);
            List<BlockchainApiProvider.TransactionInfo> transactions = apiManager.getBlockTransactions(blockHash);
            
            // 解析时间戳
            long timestamp = System.currentTimeMillis() / 1000;
            try {
                JsonNode root = objectMapper.readTree(rawJson);
                if (root.has("time")) {
                    timestamp = root.get("time").asLong();
                } else if (root.has("header") && root.get("header").has("time")) {
                    timestamp = root.get("header").get("time").asLong();
                }
            } catch (Exception e) {
                LogUtil.warn(this.getClass(), "解析区块时间戳失败，使用当前时间");
            }
            
            // 更新记录信息
            record.setBlockHash(blockHash);
            record.setBlockTimestamp(timestamp);
            record.setTransactionCount(transactions.size());
            
            // 保存到本地文件
            String filePath = saveBlockDataToFile(blockHeight, rawJson);
            record.setDataFilePath(filePath);
            record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.FETCHED);
            record.setDataFetchedAt(LocalDateTime.now());
            syncRecordRepository.save(record);
            
            LogUtil.info(this.getClass(), String.format(
                "区块数据已获取: 高度=%d, 哈希=%s, 交易数=%d, 文件=%s",
                blockHeight, blockHash, transactions.size(), filePath));
            
            // 发送到Kafka
            record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.SENDING);
            syncRecordRepository.save(record);
            
            String messageId = kafkaProducer.sendBlockData(blockHeight, rawJson);
            
            record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.SENT);
            record.setKafkaSentAt(LocalDateTime.now());
            record.setKafkaMessageId(messageId);
            syncRecordRepository.save(record);
            
            LogUtil.info(this.getClass(), "区块数据已发送到Kafka: " + blockHeight);
            
        } catch (Exception e) {
            record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            syncRecordRepository.save(record);
            throw new RuntimeException("获取区块数据失败: " + blockHeight, e);
        }
    }
    
    /**
     * 保存区块数据到本地文件
     */
    private String saveBlockDataToFile(long blockHeight, String jsonData) {
        String fileName = "block_" + blockHeight + ".json";
        String filePath = dataStoragePath + File.separator + fileName;
        
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(jsonData);
        } catch (IOException e) {
            LogUtil.error(this.getClass(), "保存区块数据文件失败: " + filePath, e);
            throw new RuntimeException("保存区块数据文件失败", e);
        }
        
        return filePath;
    }
    
    /**
     * 获取或创建同步状态记录
     */
    private BtcSyncStatus getOrCreateSyncStatus() {
        Optional<BtcSyncStatus> statusOpt = syncStatusRepository.findFirstByOrderByIdAsc();
        if (statusOpt.isPresent()) {
            return statusOpt.get();
        }
        
        // 创建新的同步状态
        BtcSyncStatus status = new BtcSyncStatus();
        status.setSyncedBlockHeight(0L);
        status.setNetworkLatestHeight(0L);
        status.setBehindBlocks(0L);
        status.setSyncState(BtcSyncStatus.SyncState.RUNNING);
        return syncStatusRepository.save(status);
    }
}
