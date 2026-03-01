package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 钱包种子实体类
 * 用于存储加密的HD钱包种子
 */
@Data
@Entity
@Table(name = "btc_wallet_seeds")
public class BtcWalletSeed {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 钱包标识符（支持多钱包）
     */
    @Column(nullable = false, unique = true, length = 50)
    private String walletId;
    
    /**
     * 加密的种子数据（Base64编码）
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedSeed;
    
    /**
     * 加密IV（初始化向量）
     */
    @Column(nullable = false, length = 100)
    private String encryptionIv;
    
    /**
     * 网络类型：mainnet/testnet
     */
    @Column(nullable = false, length = 20)
    private String network;
    
    /**
     * 是否为主钱包
     */
    @Column(name = "is_primary", nullable = false)
    private Boolean primary = true;
    
    /**
     * 是否激活
     */
    @Column(nullable = false)
    private Boolean active = true;
    
    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    /**
     * 备注信息
     */
    @Column(length = 500)
    private String remarks;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}