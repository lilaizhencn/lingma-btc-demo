package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_deposits", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tx_hash_output_index", columnNames = {"tx_hash", "output_index"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtcDeposit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 充值地址
     */
    @Column(name = "address", nullable = false, length = 100)
    private String address;
    
    /**
     * 交易哈希
     */
    @Column(name = "tx_hash", nullable = false, length = 64)
    private String txHash;
    
    /**
     * 输出索引（vout）
     */
    @Column(name = "output_index", nullable = false)
    private Integer outputIndex;
    
    /**
     * 金额（单位：聪 satoshi）
     */
    @Column(name = "amount", nullable = false)
    private Long amount;
    
    /**
     * 确认数
     */
    @Column(name = "confirmations", nullable = false)
    private Integer confirmations = 0;
    
    /**
     * 区块高度
     */
    @Column(name = "block_height")
    private Long blockHeight;
    
    /**
     * 区块时间
     */
    @Column(name = "block_time")
    private LocalDateTime blockTime;
    
    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * 充值状态：PENDING(待确认)、CONFIRMED(已确认)、COMPLETED(已完成)、PROCESSED(已处理)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DepositStatus status = DepositStatus.PENDING;
    
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
    
    public enum DepositStatus {
        PENDING,    // 待确认
        CONFIRMED,  // 已确认
        COMPLETED,  // 已完成
        PROCESSED   // 已处理
    }
}