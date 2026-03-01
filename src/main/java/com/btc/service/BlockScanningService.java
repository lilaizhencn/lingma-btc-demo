package com.btc.service;

import com.btc.entity.*;
import com.btc.util.LogUtil;
import com.btc.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * 区块扫描服务
 * 
 * 功能说明：
 * 1. 定时扫描新区块，处理充值和提现确认
 * 2. 支持手动指定扫描某个固定区块（错误纠正）
 * 3. 防重复入账：以(交易哈希, 输出索引)为唯一标识
 * 4. 延迟确认机制：6个确认后最终入账
 * 5. 区块重组检测和处理
 * 6. 内部归集处理
 */
@Service
@Transactional
public class BlockScanningService {
    
    private final BtcAddressRepository addressRepository;
    private final BtcUtxoRepository utxoRepository;
    private final BtcDepositRepository depositRepository;
    private final BtcBlockScanRecordRepository scanRecordRepository;
    private final BtcWithdrawalRepository withdrawalRepository;
    
    @Value("${blockchain.required-confirmations:6}")
    private int requiredConfirmations;
    
    @Value("${blockchain.reorg-depth:6}")
    private int reorgDepth;
    
    private static final String BLOCKCHAIN_API_BASE = "https://blockchain.info";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 速率限制相关配置 - blockchain.info 限制约为每分钟200次请求
    private static final long RATE_LIMIT_DELAY_MS = 500; // 每次请求间隔500ms
    private static final int MAX_RETRY_ATTEMPTS = 5; // 最大重试次数
    private static final long RETRY_DELAY_MS = 10000; // 重试延迟10秒
    private static final long RATE_LIMIT_COOLDOWN_MS = 60000; // 遇到429后冷却1分钟
    
    // 上次API调用时间
    private volatile long lastApiCallTime = 0;
    // 请求计数器（每分钟）
    private volatile int requestCount = 0;
    // 计数器重置时间
    private volatile long counterResetTime = System.currentTimeMillis();
    // 每分钟最大请求数
    private static final int MAX_REQUESTS_PER_MINUTE = 180;
    // 是否处于冷却状态
    private volatile boolean inCooldown = false;
    // 冷却开始时间
    private volatile long cooldownStartTime = 0;

    public BlockScanningService(
            BtcAddressRepository addressRepository, 
            BtcUtxoRepository utxoRepository, 
            BtcDepositRepository depositRepository, 
            BtcBlockScanRecordRepository scanRecordRepository,
            BtcWithdrawalRepository withdrawalRepository) {
        this.addressRepository = addressRepository;
        this.utxoRepository = utxoRepository;
        this.depositRepository = depositRepository;
        this.scanRecordRepository = scanRecordRepository;
        this.withdrawalRepository = withdrawalRepository;
    }

    /**
     * 扫描新区块
     */
    public void scanNewBlocks() {
        try {
            // 获取当前已扫描的最高区块高度
            long lastScannedHeight = getLastScannedHeight();
            long currentNetworkHeight = getCurrentNetworkHeight();
            
            if (currentNetworkHeight <= lastScannedHeight) {
                LogUtil.info(this.getClass(), "没有新区块需要扫描");
                return;
            }
            
            LogUtil.info(this.getClass(), "开始扫描区块: " + (lastScannedHeight + 1) + " 到 " + currentNetworkHeight);
            
            // 逐个扫描新区块
            for (long height = lastScannedHeight + 1; height <= currentNetworkHeight; height++) {
                scanBlock(height);
            }
            
            // 更新充值确认状态
            updateDepositConfirmations();
            
            // 更新提现确认状态
            updateWithdrawalConfirmations();
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "区块扫描失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 手动扫描指定区块（用于错误纠正）
     * 
     * @param blockHeight 指定要扫描的区块高度
     */
    public void scanSpecificBlock(long blockHeight) {
        LogUtil.info(this.getClass(), "手动扫描指定区块: " + blockHeight);
        scanBlock(blockHeight);
    }
    
    /**
     * 扫描指定区块
     */
    public void scanBlock(long blockHeight) {
        BtcBlockScanRecord scanRecord = new BtcBlockScanRecord();
        scanRecord.setBlockHeight(blockHeight);
        scanRecord.setStartedAt(LocalDateTime.now());
        scanRecord.setScanStatus(BtcBlockScanRecord.ScanStatus.PROCESSING);
        
        try {
            // 获取区块信息
            BlockInfo blockInfo = getBlockInfo(blockHeight);
            
            scanRecord.setBlockHash(blockInfo.hash());
            scanRecord.setPreviousBlockHash(blockInfo.previousHash());
            scanRecord.setBlockTime(blockInfo.time());
            
            // 检测区块重组
            if (isBlockReorgDetected(blockHeight, blockInfo.previousHash())) {
                LogUtil.warn(this.getClass(), "检测到区块重组，开始处理...");
                handleBlockReorg(blockHeight);
            }
            
            // 处理区块中的交易
            int transactionCount = processBlockTransactions(blockHeight, blockInfo);
            
            scanRecord.setTransactionCount(transactionCount);
            scanRecord.setCompletedAt(LocalDateTime.now());
            scanRecord.setScanStatus(BtcBlockScanRecord.ScanStatus.SUCCESS);
            
            scanRecordRepository.save(scanRecord);
            
            LogUtil.info(this.getClass(), "区块 " + blockHeight + " 扫描完成，处理了 " + transactionCount + " 笔交易");
            
        } catch (Exception e) {
            scanRecord.setScanStatus(BtcBlockScanRecord.ScanStatus.FAILED);
            scanRecord.setFailureReason(e.getMessage());
            scanRecord.setCompletedAt(LocalDateTime.now());
            scanRecordRepository.save(scanRecord);
            
            LogUtil.error(this.getClass(), "扫描区块失败: " + blockHeight + ", 错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检测区块重组
     */
    private boolean isBlockReorgDetected(long blockHeight, String currentPreviousHash) {
        if (blockHeight <= 0) {
            return false;
        }
        
        // 获取前一个区块的扫描记录
        Optional<BtcBlockScanRecord> prevRecordOpt = scanRecordRepository.findByBlockHeight(blockHeight - 1);
        if (prevRecordOpt.isEmpty()) {
            return false;
        }
        
        BtcBlockScanRecord prevRecord = prevRecordOpt.get();
        String recordedHash = prevRecord.getBlockHash();
        
        // 如果当前区块的父哈希与记录的前一个区块哈希不匹配，说明发生了重组
        return !currentPreviousHash.equals(recordedHash);
    }
    
    /**
     * 处理区块重组
     * 回滚到重组发生前的高度
     */
    private void handleBlockReorg(long reorgHeight) {
        long rollbackHeight = reorgHeight - 1;
        
        LogUtil.warn(this.getClass(), "处理区块重组，回滚到高度: " + rollbackHeight);
        
        // 1. 获取需要回滚的所有充值记录
        List<BtcDeposit> depositsToRollback = depositRepository.findByBlockHeightGreaterThan(rollbackHeight);
        
        // 2. 回滚充值记录和UTXO
        for (BtcDeposit deposit : depositsToRollback) {
            // 删除相关的UTXO
            utxoRepository.findByTxHashAndOutputIndex(deposit.getTxHash(), deposit.getOutputIndex())
                .ifPresent(utxo -> {
                    // 如果UTXO已经被花费，需要恢复相关地址余额
                    if (utxo.getStatus() == BtcUtxo.UtxoStatus.SPENT) {
                        addressRepository.findByAddress(utxo.getAddress())
                            .ifPresent(addr -> {
                                addr.setBalance(addr.getBalance() + utxo.getAmount());
                                addressRepository.save(addr);
                            });
                    }
                    utxoRepository.delete(utxo);
                });
            
            // 如果充值已完成，需要扣减地址余额
            if (deposit.getStatus() == BtcDeposit.DepositStatus.COMPLETED) {
                addressRepository.findByAddress(deposit.getAddress())
                    .ifPresent(addr -> {
                        addr.setBalance(addr.getBalance() - deposit.getAmount());
                        addressRepository.save(addr);
                    });
            }
            
            // 删除充值记录
            depositRepository.delete(deposit);
        }
        
        // 3. 回滚提现记录状态
        List<BtcWithdrawal> withdrawalsToRollback = withdrawalRepository.findByBlockHeightGreaterThan(rollbackHeight);
        for (BtcWithdrawal withdrawal : withdrawalsToRollback) {
            withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.BROADCASTED);
            withdrawal.setBlockHeight(null);
            withdrawal.setConfirmations(0);
            withdrawalRepository.save(withdrawal);
        }
        
        // 4. 删除区块扫描记录
        List<BtcBlockScanRecord> recordsToDelete = scanRecordRepository.findByBlockHeightGreaterThan(rollbackHeight);
        scanRecordRepository.deleteAll(recordsToDelete);
        
        LogUtil.warn(this.getClass(), "区块重组处理完成，回滚了 " + depositsToRollback.size() + " 条充值记录");
    }
    
    /**
     * 处理区块中的交易
     */
    private int processBlockTransactions(long blockHeight, BlockInfo blockInfo) {
        int processedCount = 0;
        int depositCount = 0;
        
        // 获取热钱包地址（用于识别归集交易）
        List<BtcAddress> hotWalletList = addressRepository.findByAddressType(BtcAddress.AddressType.ROOT);
        String hotWalletAddress = hotWalletList.isEmpty() ? "" : hotWalletList.getFirst().getAddress();
        
        for (String txHash : blockInfo.transactions()) {
            processTransaction(txHash, blockHeight, blockInfo.time(), hotWalletAddress);
            processedCount++;
        }
        
        // 更新扫描记录的充值数量

        scanRecordRepository.findByBlockHeight(blockHeight)
            .ifPresent(record -> {
                record.setDepositCount(depositCount);
                scanRecordRepository.save(record);
            });
        
        return processedCount;
    }
    
    /**
     * 处理单个交易
     */
    private void processTransaction(String txHash, long blockHeight, LocalDateTime blockTime, String hotWalletAddress) {
        TransactionInfo txInfo = getTransactionInfo(txHash);
        
        // 判断是否为内部归集交易
        boolean isCollectionTx = isCollectionTransaction(txInfo, hotWalletAddress);
        
        // 处理交易输出（充值）
        for (TransactionOutput output : txInfo.outputs()) {
            processTransactionOutput(txHash, output, blockHeight, blockTime, isCollectionTx);
        }
        
        // 处理交易输入（提币确认/UTXO花费）
        for (TransactionInput input : txInfo.inputs()) {
            processTransactionInput(txHash, input, blockHeight, isCollectionTx, hotWalletAddress);
        }
    }
    
    /**
     * 判断是否为内部归集交易
     * 如果有用户地址的UTXO被花费，且输出只包含热钱包地址（没有找零地址），则为归集交易
     */
    private boolean isCollectionTransaction(TransactionInfo txInfo, String hotWalletAddress) {
        if (hotWalletAddress == null || hotWalletAddress.isEmpty()) {
            return false;
        }
        
        // 获取所有输出地址（过滤掉空地址）
        Set<String> outputAddresses = txInfo.outputs().stream()
            .map(TransactionOutput::address)
            .filter(addr -> addr != null && !addr.isEmpty())
            .collect(Collectors.toSet());
        
        // 归集交易：输出只包含热钱包地址（只有一个输出地址且是热钱包）
        if (outputAddresses.size() != 1) {
            return false; // 有多个输出地址，可能有找零，不是归集交易
        }
        
        if (!outputAddresses.contains(hotWalletAddress)) {
            return false; // 唯一的输出地址不是热钱包
        }
        
        // 检查输入是否来自用户地址
        for (TransactionInput input : txInfo.inputs()) {
            Optional<BtcUtxo> utxoOpt = utxoRepository.findByTxHashAndOutputIndex(
                input.prevTxHash(), input.prevOutputIndex());
            if (utxoOpt.isPresent()) {
                BtcUtxo utxo = utxoOpt.get();
                Optional<BtcAddress> addrOpt = addressRepository.findByAddress(utxo.getAddress());
                if (addrOpt.isPresent() && addrOpt.get().getAddressType() == BtcAddress.AddressType.USER) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 处理交易输出（充值）
     * 防重复入账：以(交易哈希, 输出索引)为唯一标识
     */
    private void processTransactionOutput(String txHash, TransactionOutput output, 
                                        long blockHeight, LocalDateTime blockTime,
                                        boolean isCollectionTx) {
        // 检查地址是否在系统地址列表中
        Optional<BtcAddress> addressOpt = addressRepository.findByAddress(output.address());
        
        if (addressOpt.isEmpty()) {
            return; // 不是系统地址，跳过
        }
        
        BtcAddress address = addressOpt.get();
        
        // 幂等性检查：防止重复入账
        if (depositRepository.findByTxHashAndOutputIndex(txHash, output.index()).isPresent()) {
            LogUtil.debug(this.getClass(), "充值记录已存在，跳过: " + txHash + ":" + output.index());
            return;
        }
        
        // 幂等性检查：UTXO是否已存在
        if (utxoRepository.findByTxHashAndOutputIndex(txHash, output.index()).isPresent()) {
            LogUtil.debug(this.getClass(), "UTXO已存在，跳过: " + txHash + ":" + output.index());
            return;
        }
        
        // 创建新的UTXO记录
        BtcUtxo utxo = new BtcUtxo();
        utxo.setTxHash(txHash);
        utxo.setOutputIndex(output.index());
        utxo.setAddress(output.address());
        utxo.setAmount(output.amount());
        utxo.setStatus(BtcUtxo.UtxoStatus.UNSPENT);
        utxo.setCreatedAt(LocalDateTime.now());
        utxo.setUpdatedAt(LocalDateTime.now());
        utxoRepository.save(utxo);
        
        // 如果是归集交易且输出到热钱包，不创建充值记录（内部转账）
        if (isCollectionTx && address.getAddressType() == BtcAddress.AddressType.ROOT) {
            LogUtil.info(this.getClass(), "归集交易输出到热钱包: " + txHash + ":" + output.index() + ", 金额: " + output.amount());
            return;
        }
        
        // 创建充值记录（待确认状态）
        BtcDeposit deposit = new BtcDeposit();
        deposit.setTxHash(txHash);
        deposit.setOutputIndex(output.index());
        deposit.setAddress(output.address());
        deposit.setUserId(address.getUserId());
        deposit.setAmount(output.amount());
        deposit.setStatus(BtcDeposit.DepositStatus.PENDING);
        deposit.setConfirmations(0);
        deposit.setBlockHeight(blockHeight);
        deposit.setBlockTime(blockTime);
        deposit.setCreatedAt(LocalDateTime.now());
        deposit.setUpdatedAt(LocalDateTime.now());
        depositRepository.save(deposit);
        
        LogUtil.info(this.getClass(), "发现充值: " + txHash + ":" + output.index() + 
            ", 金额: " + output.amount() + " 聪, 地址: " + output.address());
    }
    
    /**
     * 处理交易输入（UTXO花费/提币确认）
     */
    private void processTransactionInput(String txHash, TransactionInput input, 
                                        long blockHeight, boolean isCollectionTx,
                                        String hotWalletAddress) {
        Optional<BtcUtxo> utxoOpt = utxoRepository.findByTxHashAndOutputIndex(
            input.prevTxHash(), input.prevOutputIndex());
            
        if (utxoOpt.isEmpty()) {
            return; // 不是系统管理的UTXO
        }
        
        BtcUtxo utxo = utxoOpt.get();
        
        // 检查是否已经标记为已花费
        if (utxo.getStatus() == BtcUtxo.UtxoStatus.SPENT) {
            return; // 已经处理过
        }
        
        // 标记UTXO为已花费
        utxo.setStatus(BtcUtxo.UtxoStatus.SPENT);
        utxo.setSpentTxHash(txHash);
        utxo.setUpdatedAt(LocalDateTime.now());
        utxoRepository.save(utxo);
        
        if (isCollectionTx) {
            // 归集交易：不更新转出地址余额（资产所有权未变）
            LogUtil.info(this.getClass(), "归集交易花费UTXO: " + input.prevTxHash() + ":" + input.prevOutputIndex());
        } else {
            // 检查是否为提现交易
            Optional<BtcWithdrawal> withdrawalOpt = withdrawalRepository.findByTxHash(txHash);
            if (withdrawalOpt.isPresent()) {
                // 提现确认处理
                BtcWithdrawal withdrawal = withdrawalOpt.get();
                withdrawal.setBlockHeight(blockHeight);
                withdrawal.setConfirmations(1);
                withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.CONFIRMED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);
                
                LogUtil.info(this.getClass(), "提现确认: " + txHash + ", 状态更新为CONFIRMED");
            } else {
                // 非归集、非提现：可能是用户直接转账，扣减余额
                addressRepository.findByAddress(utxo.getAddress())
                    .ifPresent(addr -> {
                        addr.setBalance(addr.getBalance() - utxo.getAmount());
                        addressRepository.save(addr);
                    });
            }
        }
    }
    
    /**
     * 更新充值确认状态
     * 达到requiredConfirmations确认数后最终入账
     */
    public void updateDepositConfirmations() {
        long currentHeight = getCurrentNetworkHeight();
        
        List<BtcDeposit> pendingDeposits = depositRepository.findByStatus(BtcDeposit.DepositStatus.PENDING);
        
        for (BtcDeposit deposit : pendingDeposits) {
            int confirmations = (int)(currentHeight - deposit.getBlockHeight()) + 1;
            deposit.setConfirmations(confirmations);
            
            if (confirmations >= requiredConfirmations) {
                completeDeposit(deposit);
            } else {
                depositRepository.save(deposit);
            }
        }
        
        // 处理已确认但未完成的充值
        List<BtcDeposit> confirmedDeposits = depositRepository.findByStatus(BtcDeposit.DepositStatus.CONFIRMED);
        for (BtcDeposit deposit : confirmedDeposits) {
            int confirmations = (int)(currentHeight - deposit.getBlockHeight()) + 1;
            deposit.setConfirmations(confirmations);
            if (confirmations >= requiredConfirmations) {
                completeDeposit(deposit);
            } else {
                depositRepository.save(deposit);
            }
        }
    }
    
    /**
     * 完成充值（达到确认数后入账）
     */
    private void completeDeposit(BtcDeposit deposit) {
        deposit.setStatus(BtcDeposit.DepositStatus.COMPLETED);
        deposit.setCompletedAt(LocalDateTime.now());
        depositRepository.save(deposit);
        
        // 更新地址余额
        Optional<BtcAddress> addressOpt = addressRepository.findByAddress(deposit.getAddress());
        if (addressOpt.isPresent()) {
            BtcAddress address = addressOpt.get();
            address.setBalance(address.getBalance() + deposit.getAmount());
            addressRepository.save(address);
        }
        
        LogUtil.info(this.getClass(), "充值完成入账: " + deposit.getTxHash() + ":" + deposit.getOutputIndex() + 
            ", 金额: " + deposit.getAmount() + " 聪, 确认数: " + deposit.getConfirmations());
    }
    
    /**
     * 更新提现确认状态
     */
    public void updateWithdrawalConfirmations() {
        long currentHeight = getCurrentNetworkHeight();
        
        List<BtcWithdrawal> confirmedWithdrawals = withdrawalRepository.findByStatus(BtcWithdrawal.WithdrawalStatus.CONFIRMED);
        
        for (BtcWithdrawal withdrawal : confirmedWithdrawals) {
            if (withdrawal.getBlockHeight() != null) {
                int confirmations = (int)(currentHeight - withdrawal.getBlockHeight()) + 1;
                withdrawal.setConfirmations(confirmations);
                
                if (confirmations >= requiredConfirmations) {
                    withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.COMPLETED);
                    withdrawal.setCompletedAt(LocalDateTime.now());
                }
                withdrawalRepository.save(withdrawal);
            }
        }
    }
    
    // ============== API调用方法 ==============
    
    /**
     * 获取最后扫描的区块高度
     */
    private Long getLastScannedHeight() {
        return scanRecordRepository.findMaxBlockHeight().orElse(0L);
    }
    
    /**
     * 获取当前网络区块高度
     */
    private Long getCurrentNetworkHeight() {
        try {
            String url = BLOCKCHAIN_API_BASE + "/q/getblockcount";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Long.parseLong(response.getBody().trim());
            }
            throw new RuntimeException("获取区块高度失败");
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取网络区块高度失败: " + e.getMessage(), e);
            throw new RuntimeException("无法连接到blockchain.info API");
        }
    }
    
    /**
     * 获取区块信息
     */
    private BlockInfo getBlockInfo(long blockHeight) {
        return getBlockInfoWithRetry(blockHeight, 0);
    }
    
    /**
     * 带重试机制的获取区块信息
     */
    private BlockInfo getBlockInfoWithRetry(long blockHeight, int retryCount) {
        try {
            // 速率限制控制
            enforceRateLimit();
            
            String blockHash = getBlockHash(blockHeight);
            String url = BLOCKCHAIN_API_BASE + "/rawblock/" + blockHash;
            
            LogUtil.debug(this.getClass(), "请求区块信息: " + url);
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                String hash = root.has("hash") ? root.get("hash").asText() : "";
                String prevHash = root.has("prev_block") ? root.get("prev_block").asText() : "";
                long timestamp = root.has("time") ? root.get("time").asLong() : System.currentTimeMillis() / 1000;
                LocalDateTime blockTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochSecond(timestamp), 
                    java.time.ZoneId.systemDefault());
                
                List<String> transactions = new ArrayList<>();
                if (root.has("tx") && root.get("tx").isArray()) {
                    for (JsonNode tx : root.get("tx")) {
                        if (tx.has("hash")) {
                            transactions.add(tx.get("hash").asText());
                        }
                    }
                }
                
                return new BlockInfo(hash, prevHash, blockTime, transactions);
            }
            throw new RuntimeException("获取区块信息失败，HTTP状态码: " + response.getStatusCode());
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制，重试次数: " + retryCount + ", 区块高度: " + blockHeight);
            
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * (retryCount + 1)); // 指数退避
                    return getBlockInfoWithRetry(blockHeight, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            } else {
                LogUtil.error(this.getClass(), "达到最大重试次数，放弃获取区块信息: " + blockHeight);
                throw new RuntimeException("API速率限制，无法获取区块信息: " + blockHeight, e);
            }
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取区块信息失败: " + blockHeight + ", 重试次数: " + retryCount, e);
            
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                    return getBlockInfoWithRetry(blockHeight, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            }
            throw new RuntimeException("获取区块信息失败: " + blockHeight, e);
        }
    }
    
    /**
     * 获取区块哈希
     */
    private String getBlockHash(long blockHeight) {
        return getBlockHashWithRetry(blockHeight, 0);
    }
    
    /**
     * 带重试机制的获取区块哈希
     */
    private String getBlockHashWithRetry(long blockHeight, int retryCount) {
        try {
            // 速率限制控制
            enforceRateLimit();
            
            String url = BLOCKCHAIN_API_BASE + "/block-height/" + blockHeight + "?format=json";
            
            LogUtil.debug(this.getClass(), "请求区块哈希: " + url);
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("blocks") && !root.get("blocks").isEmpty()) {
                    return root.get("blocks").get(0).get("hash").asText();
                }
            }
            throw new RuntimeException("未找到指定高度的区块，HTTP状态码: " + response.getStatusCode());
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制获取区块哈希，重试次数: " + retryCount + ", 区块高度: " + blockHeight);
            
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
                    return getBlockHashWithRetry(blockHeight, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            } else {
                LogUtil.error(this.getClass(), "达到最大重试次数，放弃获取区块哈希: " + blockHeight);
                throw new RuntimeException("API速率限制，无法获取区块哈希: " + blockHeight, e);
            }
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取区块哈希失败: " + blockHeight + ", 重试次数: " + retryCount, e);
            
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                    return getBlockHashWithRetry(blockHeight, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            }
            throw new RuntimeException("获取区块哈希失败: " + blockHeight, e);
        }
    }
    
    /**
     * 获取交易详细信息
     */
    private TransactionInfo getTransactionInfo(String txHash) {
        return getTransactionInfoWithRetry(txHash, 0);
    }
    
    /**
     * 带重试机制的获取交易信息
     */
    private TransactionInfo getTransactionInfoWithRetry(String txHash, int retryCount) {
        try {
            // 速率限制控制
            enforceRateLimit();
            
            String url = BLOCKCHAIN_API_BASE + "/rawtx/" + txHash;
            
            LogUtil.debug(this.getClass(), "请求交易信息: " + url);
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                List<TransactionInput> inputs = new ArrayList<>();
                if (root.has("inputs") && root.get("inputs").isArray()) {
                    inputs = StreamSupport.stream(root.get("inputs").spliterator(), false)
                        .map(inputNode -> {
                            String prevTxHash = "coinbase";
                            int prevOutputIndex = 0;
                            if (inputNode.has("prev_out")) {
                                JsonNode prevOut = inputNode.get("prev_out");
                                if (prevOut.has("n")) {
                                    prevOutputIndex = prevOut.get("n").asInt();
                                }
                            }
                            // 尝试获取前一交易的哈希
                            if (inputNode.has("prev_out") && inputNode.get("prev_out").has("tx_index")) {
                                prevTxHash = String.valueOf(inputNode.get("prev_out").get("tx_index").asLong());
                            }
                            return new TransactionInput(prevTxHash, prevOutputIndex);
                        })
                        .collect(Collectors.toList());
                }
                
                List<TransactionOutput> outputs = new ArrayList<>();
                if (root.has("out") && root.get("out").isArray()) {
                    outputs = StreamSupport.stream(root.get("out").spliterator(), false)
                        .map(outputNode -> {
                            int index = outputNode.has("n") ? outputNode.get("n").asInt() : 0;
                            String address = outputNode.has("addr") ? outputNode.get("addr").asText() : "";
                            long amount = outputNode.has("value") ? outputNode.get("value").asLong() : 0L;
                            return new TransactionOutput(index, address, amount);
                        })
                        .collect(Collectors.toList());
                }
                
                LocalDateTime blockTime = LocalDateTime.now();
                if (root.has("block_height")) {
                    try {
                        blockTime = getBlockTime(root.get("block_height").asLong());
                    } catch (Exception e) {
                        // 忽略错误，使用默认时间
                    }
                }
                
                return new TransactionInfo(inputs, outputs, blockTime);
            }
            throw new RuntimeException("获取交易信息失败，HTTP状态码: " + response.getStatusCode());
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制获取交易信息，重试次数: " + retryCount + ", 交易哈希: " + txHash);
            
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
                    return getTransactionInfoWithRetry(txHash, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            } else {
                LogUtil.error(this.getClass(), "达到最大重试次数，放弃获取交易信息: " + txHash);
                return new TransactionInfo(new ArrayList<>(), new ArrayList<>(), LocalDateTime.now());
            }
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取交易信息失败: " + txHash + ", 重试次数: " + retryCount, e);
            
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                    return getTransactionInfoWithRetry(txHash, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试过程中被中断", ie);
                }
            }
            return new TransactionInfo(new ArrayList<>(), new ArrayList<>(), LocalDateTime.now());
        }
    }
    
    /**
     * 获取区块时间
     */
    private LocalDateTime getBlockTime(long blockHeight) {
        try {
            BlockInfo blockInfo = getBlockInfo(blockHeight);
            return blockInfo.time();
        } catch (Exception e) {
            LogUtil.warn(this.getClass(), "获取区块时间失败，使用当前时间: " + blockHeight);
            return LocalDateTime.now();
        }
    }
    
    /**
     * 执行速率限制控制
     */
    private synchronized void enforceRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCall = currentTime - lastApiCallTime;
        
        if (timeSinceLastCall < RATE_LIMIT_DELAY_MS) {
            long sleepTime = RATE_LIMIT_DELAY_MS - timeSinceLastCall;
            try {
                LogUtil.debug(this.getClass(), "执行速率限制延迟: " + sleepTime + "ms");
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("速率限制延迟被中断", e);
            }
        }
        
        lastApiCallTime = System.currentTimeMillis();
    }
    
    /**
     * 判断异常是否应该重试
     */
    private boolean shouldRetry(Exception e) {
        // 网络相关异常应该重试
        return e instanceof SocketTimeoutException || 
               e instanceof ConnectException ||
               e instanceof UnknownHostException ||
               e instanceof HttpServerErrorException ||
               (e instanceof HttpClientErrorException && 
                ((HttpClientErrorException) e).getStatusCode().is5xxServerError());
    }
    
    // ============== 数据记录类 ==============
    
    public record BlockInfo(
        String hash,
        String previousHash,
        LocalDateTime time,
        List<String> transactions
    ) {}
    
    public record TransactionInfo(
        List<TransactionInput> inputs,
        List<TransactionOutput> outputs,
        LocalDateTime blockTime
    ) {
        public TransactionInfo {
            inputs = inputs != null ? inputs : new ArrayList<>();
            outputs = outputs != null ? outputs : new ArrayList<>();
            blockTime = blockTime != null ? blockTime : LocalDateTime.now();
        }
    }
    
    public record TransactionInput(String prevTxHash, int prevOutputIndex) {}
    
    public record TransactionOutput(int index, String address, long amount) {}
}