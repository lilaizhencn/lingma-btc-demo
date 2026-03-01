package com.btc.repository;

import com.btc.entity.BtcUtxo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtcUtxoRepository extends JpaRepository<BtcUtxo, Long> {
    
    /**
     * 根据交易哈希和输出索引查找UTXO（用于幂等性检查）
     */
    Optional<BtcUtxo> findByTxHashAndOutputIndex(String txHash, Integer outputIndex);
    
    /**
     * 根据地址查找未花费的UTXO
     */
    List<BtcUtxo> findByAddressAndStatus(String address, BtcUtxo.UtxoStatus status);
    
    /**
     * 查找指定地址的可用UTXO（未花费且未锁定）
     */
    @Query("SELECT u FROM BtcUtxo u WHERE u.address = :address AND u.status = 'UNSPENT' ORDER BY u.amount ASC")
    List<BtcUtxo> findAvailableUtxosByAddress(@Param("address") String address);
    
    /**
     * 锁定指定的UTXO（悲观锁）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM BtcUtxo u WHERE u.id = :id")
    Optional<BtcUtxo> findByIdForUpdate(@Param("id") Long id);
    
    /**
     * 查找已锁定且过期的UTXO
     */
    @Query("SELECT u FROM BtcUtxo u WHERE u.status = 'LOCKED' AND u.lockExpireTime < :currentTime")
    List<BtcUtxo> findExpiredLockedUtxos(@Param("currentTime") LocalDateTime currentTime);
    
    /**
     * 解锁过期的UTXO
     */
    @Modifying
    @Query("UPDATE BtcUtxo u SET u.status = 'UNSPENT', u.lockTime = null, u.lockExpireTime = null, u.withdrawalId = null " +
           "WHERE u.status = 'LOCKED' AND u.lockExpireTime < :currentTime")
    int unlockExpiredUtxos(@Param("currentTime") LocalDateTime currentTime);
    
    /**
     * 统计地址的总余额
     */
    @Query("SELECT COALESCE(SUM(u.amount), 0) FROM BtcUtxo u WHERE u.address = :address AND u.status = 'UNSPENT'")
    Long sumUnspentAmountByAddress(@Param("address") String address);
    
    /**
     * 统计各种状态的UTXO数量
     */
    @Query("SELECT COUNT(u) FROM BtcUtxo u WHERE u.status = :status")
    Long countByStatus(@Param("status") BtcUtxo.UtxoStatus status);
    
    /**
     * 查找关联特定提币申请的UTXO
     */
    List<BtcUtxo> findByWithdrawalId(Long withdrawalId);
}