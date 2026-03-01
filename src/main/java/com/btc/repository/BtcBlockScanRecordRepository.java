package com.btc.repository;

import com.btc.entity.BtcBlockScanRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtcBlockScanRecordRepository extends JpaRepository<BtcBlockScanRecord, Long> {
    
    /**
     * 查找最大区块高度
     */
    @Query("SELECT MAX(b.blockHeight) FROM BtcBlockScanRecord b")
    Optional<Long> findMaxBlockHeight();
    
    /**
     * 根据区块高度查找记录
     */
    Optional<BtcBlockScanRecord> findByBlockHeight(Long blockHeight);
    
    /**
     * 根据扫描状态查找记录
     */
    List<BtcBlockScanRecord> findByScanStatus(BtcBlockScanRecord.ScanStatus status);
    
    /**
     * 查找区块高度大于指定值的所有记录（用于区块重组回滚）
     */
    List<BtcBlockScanRecord> findByBlockHeightGreaterThan(Long blockHeight);
}