package com.btc.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_addresses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtcAddress {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 地址字符串
     */
    @Column(name = "address", nullable = false, unique = true, length = 100)
    private String address;
    
    /**
     * 地址类型：USER(用户充值地址)、HOT_WALLET(热钱包地址)、INTERNAL(内部查看地址)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "address_type", nullable = false)
    private AddressType addressType;
    
    /**
     * HD钱包派生路径（如：m/44'/0'/0'/0/0）
     */
    @Column(name = "derivation_path", length = 100)
    private String derivationPath;
    
    /**
     * BIP44路径 - coin_type (币种类型)
     * BTC主网=0, 测试网=1, LTC=2, DASH=5, DOGE=3
     */
    @Column(name = "coin_type")
    private Integer coinType;
    
    /**
     * BIP44路径 - account (账户/用户ID)
     * Root地址=0, 用户地址=用户ID
     */
    @Column(name = "account")
    private Long account;
    
    /**
     * BIP44路径 - change (业务ID/链类型)
     * 0=外部链(充值地址), 1=内部链(找零地址)
     */
    @Column(name = "change_index")
    private Integer changeIndex;
    
    /**
     * BIP44路径 - address_index (地址索引)
     * 每个用户可以生成多个地址，递增
     */
    @Column(name = "address_index")
    private Integer addressIndex;
    
    /**
     * 用户ID（用户充值地址时使用，等同于account字段）
     */
    @Column(name = "user_id")
    private Long userId;
    
    /**
     * 当前余额（单位：聪 satoshi）
     */
    @Column(name = "balance", nullable = false)
    private Long balance = 0L;
    
    /**
     * 是否激活
     */
    @Column(name = "active", nullable = false)
    private Boolean active = true;
    
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
    
    public enum AddressType {
        ROOT,       // 根地址（系统主地址，也作为热钱包地址使用）
        USER,       // 用户充值地址
        INTERNAL    // 内部查看地址
    }
}