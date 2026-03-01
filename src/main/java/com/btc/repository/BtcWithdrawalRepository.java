package com.btc.repository;

import com.btc.entity.BtcWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtcWithdrawalRepository extends JpaRepository<BtcWithdrawal, Long> {
    
    /**
     * 根据状态查找提币记录
     */
    List<BtcWithdrawal> findByStatus(BtcWithdrawal.WithdrawalStatus status);
    
    /**
     * 根据用户ID查找提币记录
     */
    List<BtcWithdrawal> findByUserId(Long userId);
    
    /**
     * 根据交易哈希查找提币记录
     */
    Optional<BtcWithdrawal> findByTxHash(String txHash);
    
    /**
     * 查找指定状态且重试次数小于限制的记录
     */
    List<BtcWithdrawal> findByStatusAndRetryCountLessThan(
        BtcWithdrawal.WithdrawalStatus status, Integer maxRetries);
    
    /**
     * 查找区块高度大于指定值的所有提现记录（用于区块重组回滚）
     */
    List<BtcWithdrawal> findByBlockHeightGreaterThan(Long blockHeight);
}