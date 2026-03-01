package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 区块同步记录表
 * 记录区块数据同步状态，包括已同步高度、主网最新高度、发送到Kafka的时间等
 */
@Data
@Entity
@Table(name = "btc_block_sync_record")
public class BtcBlockSyncRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 区块高度
     */
    @Column(nullable = false, unique = true)
    private Long blockHeight;
    
    /**
     * 区块哈希
     */
    @Column(length = 64)
    private String blockHash;
    
    /**
     * 区块时间戳
     */
    private Long blockTimestamp;
    
    /**
     * 交易数量
     */
    private Integer transactionCount;
    
    /**
     * 区块数据文件路径（本地存储）
     */
    @Column(length = 512)
    private String dataFilePath;
    
    /**
     * 同步状态
     */
    @Enumerated(EnumType.STRING)
    private SyncStatus syncStatus;
    
    /**
     * 数据获取时间
     */
    private LocalDateTime dataFetchedAt;
    
    /**
     * 发送到Kafka的时间
     */
    private LocalDateTime kafkaSentAt;
    
    /**
     * Kafka消息ID
     */
    @Column(length = 128)
    private String kafkaMessageId;
    
    /**
     * 业务处理状态
     */
    @Enumerated(EnumType.STRING)
    private ProcessStatus processStatus;
    
    /**
     * 业务处理时间
     */
    private LocalDateTime processedAt;
    
    /**
     * 错误信息
     */
    @Column(length = 1024)
    private String errorMessage;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 同步状态枚举
     */
    public enum SyncStatus {
        PENDING,        // 待同步
        FETCHING,       // 正在获取数据
        FETCHED,        // 数据已获取
        SENDING,        // 正在发送到Kafka
        SENT,           // 已发送到Kafka
        FAILED          // 同步失败
    }
    
    /**
     * 业务处理状态枚举
     */
    public enum ProcessStatus {
        PENDING,        // 待处理
        PROCESSING,     // 处理中
        COMPLETED,      // 处理完成
        FAILED          // 处理失败
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (syncStatus == null) {
            syncStatus = SyncStatus.PENDING;
        }
        if (processStatus == null) {
            processStatus = ProcessStatus.PENDING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}