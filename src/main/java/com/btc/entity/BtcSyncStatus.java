package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 区块同步状态表
 * 记录整体同步进度，包括已同步完成的区块高度、主网最新高度、最后更新时间等
 */
@Data
@Entity
@Table(name = "btc_sync_status")
public class BtcSyncStatus {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 已同步完成的区块高度
     */
    @Column(nullable = false)
    private Long syncedBlockHeight;
    
    /**
     * 主网最新区块高度
     */
    @Column(nullable = false)
    private Long networkLatestHeight;
    
    /**
     * 落后区块数（主网高度 - 已同步高度）
     */
    private Long behindBlocks;
    
    /**
     * 最后一次同步时间
     */
    private LocalDateTime lastSyncAt;
    
    /**
     * 最后一次发送到Kafka的时间
     */
    private LocalDateTime lastKafkaSentAt;
    
    /**
     * 同步状态：RUNNING-运行中, PAUSED-暂停, ERROR-错误
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SyncState syncState;
    
    /**
     * 最后错误信息
     */
    @Column(length = 1024)
    private String lastError;
    
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
    public enum SyncState {
        RUNNING,    // 运行中
        PAUSED,     // 暂停
        ERROR       // 错误
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (syncState == null) {
            syncState = SyncState.RUNNING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}