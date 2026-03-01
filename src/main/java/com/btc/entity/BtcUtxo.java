package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_utxos", uniqueConstraints = {
    @UniqueConstraint(name = "uk_utxo_tx_hash_output_index", columnNames = {"tx_hash", "output_index"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtcUtxo {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 所属地址
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
     * 脚本公钥
     */
    @Column(name = "script_pubkey", length = 200)
    private String scriptPubkey;
    
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
     * 是否已花费
     */
    @Column(name = "spent", nullable = false)
    private Boolean spent = false;
    
    /**
     * 花费该UTXO的交易哈希
     */
    @Column(name = "spent_tx_hash", length = 64)
    private String spentTxHash;
    
    /**
     * 锁定时间
     */
    @Column(name = "lock_time")
    private LocalDateTime lockTime;
    
    /**
     * 锁定过期时间
     */
    @Column(name = "lock_expire_time")
    private LocalDateTime lockExpireTime;
    
    /**
     * 关联的提现ID
     */
    @Column(name = "withdrawal_id")
    private Long withdrawalId;
    
    /**
     * UTXO状态：AVAILABLE(可用)、PENDING(待确认)、SPENT(已花费)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UtxoStatus status = UtxoStatus.AVAILABLE;
    
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
    
    public enum UtxoStatus {
        UNSPENT,    // 未花费 (等同于AVAILABLE)
        AVAILABLE,  // 可用
        PENDING,    // 待确认
        LOCKED,     // 锁定中
        SPENT       // 已花费
    }
}