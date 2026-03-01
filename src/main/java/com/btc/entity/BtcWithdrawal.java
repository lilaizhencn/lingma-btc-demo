package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_withdrawals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtcWithdrawal {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 目标地址
     */
    @Column(name = "to_address", nullable = false, length = 100)
    private String toAddress;
    
    /**
     * 金额（单位：聪 satoshi）
     */
    @Column(name = "amount", nullable = false)
    private Long amount;
    
    /**
     * 手续费（单位：聪 satoshi）
     */
    @Column(name = "fee", nullable = false)
    private Long fee;
    
    /**
     * 交易哈希
     */
    @Column(name = "tx_hash", length = 64)
    private String txHash;
    
    /**
     * 原始交易hex
     */
    @Column(name = "raw_tx", columnDefinition = "TEXT")
    private String rawTx;
    
    /**
     * 提现状态：PENDING(待处理)、SIGNED(已签名)、BROADCASTED(已广播)、CONFIRMED(已确认)、FAILED(失败)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WithdrawalStatus status = WithdrawalStatus.PENDING;
    
    /**
     * 失败原因
     */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    
    /**
     * 确认数
     */
    @Column(name = "confirmations")
    private Integer confirmations;
    
    /**
     * 区块高度
     */
    @Column(name = "block_height")
    private Long blockHeight;
    
    /**
     * 重试次数
     */
    @Column(name = "retry_count")
    private Integer retryCount = 0;
    
    /**
     * 冻结时间
     */
    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;
    
    /**
     * 批准时间
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    
    /**
     * 处理时间
     */
    @Column(name = "processing_at")
    private LocalDateTime processingAt;
    
    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * 总金额（包含手续费）
     */
    @Column(name = "total_amount")
    private Long totalAmount;
    
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
    
    public enum WithdrawalStatus {
        PENDING,      // 待处理
        APPROVED,     // 已批准
        REJECTED,     // 已拒绝
        PROCESSING,   // 处理中
        SIGNED,       // 已签名
        BROADCASTED,  // 已广播
        CONFIRMED,    // 已确认
        COMPLETED,    // 已完成
        FAILED        // 失败
    }
}