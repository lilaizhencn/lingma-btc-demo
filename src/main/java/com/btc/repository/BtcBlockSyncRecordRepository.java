package com.btc.repository;

import com.btc.entity.BtcBlockSyncRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 区块同步记录Repository
 */
@Repository
public interface BtcBlockSyncRecordRepository extends JpaRepository<BtcBlockSyncRecord, Long> {
    
    /**
     * 根据区块高度查找
     */
    Optional<BtcBlockSyncRecord> findByBlockHeight(Long blockHeight);
    
    /**
     * 查找指定高度之后的记录
     */
    List<BtcBlockSyncRecord> findByBlockHeightGreaterThan(Long blockHeight);
    
    /**
     * 查找待同步的记录
     */
    List<BtcBlockSyncRecord> findBySyncStatus(BtcBlockSyncRecord.SyncStatus syncStatus);
    
    /**
     * 查找待处理的记录（已发送到Kafka但未处理）
     */
    List<BtcBlockSyncRecord> findBySyncStatusAndProcessStatus(
        BtcBlockSyncRecord.SyncStatus syncStatus, 
        BtcBlockSyncRecord.ProcessStatus processStatus);
    
    /**
     * 获取最大区块高度
     */
    @Query("SELECT MAX(b.blockHeight) FROM BtcBlockSyncRecord b")
    Optional<Long> findMaxBlockHeight();
    
    /**
     * 获取已同步完成的最大区块高度（业务处理完成的）
     */
    @Query("SELECT MAX(b.blockHeight) FROM BtcBlockSyncRecord b WHERE b.processStatus = 'COMPLETED'")
    Optional<Long> findMaxCompletedBlockHeight();
    
    /**
     * 查找失败的记录
     */
    List<BtcBlockSyncRecord> findBySyncStatusOrProcessStatus(
        BtcBlockSyncRecord.SyncStatus syncStatus,
        BtcBlockSyncRecord.ProcessStatus processStatus);
}