package com.btc.repository;

import com.btc.entity.BtcWalletSeed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 钱包种子Repository
 */
@Repository
public interface BtcWalletSeedRepository extends JpaRepository<BtcWalletSeed, Long> {
    
    /**
     * 根据钱包ID查找
     */
    Optional<BtcWalletSeed> findByWalletId(String walletId);
    
    /**
     * 查找主钱包
     */
    Optional<BtcWalletSeed> findByPrimaryTrueAndActiveTrue();
    
    /**
     * 根据网络类型查找主钱包
     */
    Optional<BtcWalletSeed> findByPrimaryTrueAndActiveTrueAndNetwork(String network);
    
    /**
     * 检查钱包ID是否存在
     */
    boolean existsByWalletId(String walletId);
    
    /**
     * 检查是否存在主钱包
     */
    boolean existsByPrimaryTrueAndActiveTrue();
}