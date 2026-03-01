package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_block_scan_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtcBlockScanRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 区块高度
     */
    @Column(name = "block_height", nullable = false, unique = true)
    private Long blockHeight;
    
    /**
     * 区块哈希
     */
    @Column(name = "block_hash", nullable = false, length = 64)
    private String blockHash;
    
    /**
     * 父区块哈希
     */
    @Column(name = "previous_block_hash", length = 64)
    private String previousBlockHash;
    
    /**
     * 区块时间戳
     */
    @Column(name = "block_time", nullable = false)
    private LocalDateTime blockTime;
    
    /**
     * 扫描状态：SUCCESS(成功)、FAILED(失败)、PROCESSING(处理中)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ScanStatus scanStatus;
    
    /**
     * 处理的交易数量
     */
    @Column(name = "transaction_count")
    private Integer transactionCount = 0;
    
    /**
     * 发现的充值数量
     */
    @Column(name = "deposit_count")
    private Integer depositCount = 0;
    
    /**
     * 失败原因
     */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    
    /**
     * 开始扫描时间
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    /**
     * 完成扫描时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public enum ScanStatus {
        SUCCESS,    // 成功
        FAILED,     // 失败
        PROCESSING  // 处理中
    }
}