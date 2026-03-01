package com.btc.repository;

import com.btc.entity.BtcSyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 区块同步状态Repository
 */
@Repository
public interface BtcSyncStatusRepository extends JpaRepository<BtcSyncStatus, Long> {
    
    /**
     * 获取同步状态记录（通常只有一条）
     */
    Optional<BtcSyncStatus> findFirstByOrderByIdAsc();
}