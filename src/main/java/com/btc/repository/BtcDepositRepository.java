package com.btc.repository;

import com.btc.entity.BtcDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtcDepositRepository extends JpaRepository<BtcDeposit, Long> {
    
    /**
     * 根据状态查找充值记录
     */
    List<BtcDeposit> findByStatus(BtcDeposit.DepositStatus status);
    
    /**
     * 根据用户ID查找充值记录
     */
    List<BtcDeposit> findByUserId(Long userId);
    
    /**
     * 根据地址查找充值记录
     */
    List<BtcDeposit> findByAddress(String address);
    
    /**
     * 根据交易哈希和输出索引查找（用于幂等性检查）
     */
    Optional<BtcDeposit> findByTxHashAndOutputIndex(String txHash, Integer outputIndex);
    
    /**
     * 查找指定时间段内的充值记录
     */
    @Query("SELECT d FROM BtcDeposit d WHERE d.createdAt BETWEEN ?1 AND ?2")
    List<BtcDeposit> findByCreatedAtBetween(java.time.LocalDateTime start, java.time.LocalDateTime end);
    
    /**
     * 查找区块高度大于指定值的所有充值记录（用于区块重组回滚）
     */
    List<BtcDeposit> findByBlockHeightGreaterThan(Long blockHeight);
}