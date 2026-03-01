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
     * 地址类型：ROOT(热钱包地址)、USER(用户充值地址)、INTERNAL(内部查看地址)
     * ROOT地址: 热钱包主地址，BIP44路径为 m/44'/coin_type'/0'/0/0
     *          测试网路径: m/44'/1'/0'/0/0 (coin_type=1)
     *          主网路径: m/44'/0'/0'/0/0 (coin_type=0)
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
        /**
         * ROOT 类型地址是热钱包主地址，根据 BIP44 标准生成：
         *
         * - __测试网路径__: `m/44'/1'/0'/0/0` (coin_type=1)
         * - __主网路径__: `m/44'/0'/0'/0/0` (coin_type=0)
         *
         * 路径结构：`m/44'/coin_type'/account'/change/address_index`
         *
         * - coin_type: 1(测试网) 或 0(主网)
         * - account: 0
         * - change: 0
         * - address_index: 0
         */
        ROOT,       // 热钱包地址（root 私钥生成的 testnet 路径为1 0 0 0 mainnet 为 0 0 0 0）
        USER,       // 用户充值地址
        INTERNAL    // 内部查看地址
    }
}