package com.btc.scheduler;

import com.btc.service.BlockDataFetchService;
import com.btc.service.BlockScanningService;
import com.btc.service.WithdrawalService;
import com.btc.util.LogUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 比特币服务定时任务调度器
 * 
 * 任务说明：
 * 1. 区块数据同步任务：每10分钟执行，从blockchain.info下载区块数据，保存到本地文件，发送到Kafka
 * 2. Kafka消费者自动消费区块数据，执行业务处理逻辑
 */
@Component
public class BitcoinScheduler {
    
    @Autowired
    private BlockDataFetchService blockDataFetchService;
    
    @Autowired
    private BlockScanningService blockScanningService;
    
    @Autowired
    private WithdrawalService withdrawalService;
    
    @Value("${scheduler.block-scan.enabled:true}")
    private boolean blockScanEnabled;
    
    @Value("${scheduler.withdrawal.enabled:true}")
    private boolean withdrawalEnabled;
    
    @Value("${scheduler.collection.enabled:true}")
    private boolean collectionEnabled;
    
    /**
     * 区块数据同步任务 - 每10分钟执行一次
     * 从blockchain.info下载区块数据，保存到本地文件，发送到Kafka
     * Kafka消费者自动消费数据进行业务处理
     */
    @Scheduled(cron = "${scheduler.block-scan.cron:0 */10 * * * ?}")
    public void syncBlockData() {
        if (!blockScanEnabled) {
            return;
        }
        
        try {
            LogUtil.info(this.getClass(), "开始执行区块数据同步任务...");
            blockDataFetchService.syncBlockData();
            LogUtil.info(this.getClass(), "区块数据同步任务执行完成");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "区块数据同步任务执行失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新充值确认状态 - 每分钟执行一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void updateDepositConfirmations() {
        try {
            LogUtil.info(this.getClass(), "开始更新充值确认状态...");
            blockScanningService.updateDepositConfirmations();
            LogUtil.info(this.getClass(), "充值确认状态更新完成");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "更新充值确认状态失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理提币申请 - 每5分钟执行一次
     */
    @Scheduled(cron = "${scheduler.withdrawal.cron:0 */5 * * * ?}")
    public void processWithdrawals() {
        if (!withdrawalEnabled) {
            return;
        }
        
        try {
            LogUtil.info(this.getClass(), "开始处理提币申请...");
            withdrawalService.processApprovedWithdrawals();
            LogUtil.info(this.getClass(), "提币申请处理完成");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "处理提币申请失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 清理过期的UTXO锁定 - 每10分钟执行一次
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void cleanupExpiredLocks() {
        try {
            LogUtil.info(this.getClass(), "开始清理过期的UTXO锁定...");
            withdrawalService.cleanupExpiredLocks();
            LogUtil.info(this.getClass(), "UTXO锁定清理完成");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "清理过期UTXO锁定失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 内部归集任务 - 每小时执行一次
     */
    @Scheduled(cron = "${scheduler.collection.cron:0 0 */1 * * ?}")
    public void collectFunds() {
        if (!collectionEnabled) {
            return;
        }
        
        try {
            LogUtil.info(this.getClass(), "开始执行内部归集任务...");
            performCollection();
            LogUtil.info(this.getClass(), "内部归集任务执行完成");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "内部归集任务执行失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 执行归集操作（简化实现）
     */
    private void performCollection() {
        LogUtil.info(this.getClass(), "执行归集操作...");
    }
}