package com.btc.service;

import com.btc.entity.*;
import com.btc.repository.*;
import com.btc.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 区块数据Kafka消费者服务
 * 负责从Kafka消费区块数据，执行业务处理逻辑
 */
@Service
public class BlockDataKafkaConsumer {
    
    private final BtcBlockSyncRecordRepository syncRecordRepository;
    private final BtcAddressRepository addressRepository;
    private final BtcUtxoRepository utxoRepository;
    private final BtcDepositRepository depositRepository;
    private final BtcWithdrawalRepository withdrawalRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${blockchain.required-confirmations:6}")
    private int requiredConfirmations;
    
    @Autowired
    public BlockDataKafkaConsumer(
            BtcBlockSyncRecordRepository syncRecordRepository,
            BtcAddressRepository addressRepository,
            BtcUtxoRepository utxoRepository,
            BtcDepositRepository depositRepository,
            BtcWithdrawalRepository withdrawalRepository) {
        this.syncRecordRepository = syncRecordRepository;
        this.addressRepository = addressRepository;
        this.utxoRepository = utxoRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
    }
    
    /**
     * 消费区块数据
     * 使用手动确认模式，确保消息处理成功后再提交offset
     */
    @KafkaListener(
        topics = "${spring.kafka.topic.block-data:bitcoin-block-data}",
        groupId = "btc-block-processor",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeBlockData(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment acknowledgment) {
        
        long blockHeight = Long.parseLong(key);
        LogUtil.info(this.getClass(), "收到区块数据: topic=" + topic + ", key=" + key);
        
        try {
            // 查找同步记录，如果不存在则创建新记录
            Optional<BtcBlockSyncRecord> recordOpt = syncRecordRepository.findByBlockHeight(blockHeight);
            BtcBlockSyncRecord record;
            if (recordOpt.isEmpty()) {
                LogUtil.warn(this.getClass(), "未找到区块同步记录，创建新记录: " + blockHeight);
                record = new BtcBlockSyncRecord();
                record.setBlockHeight(blockHeight);
                record.setSyncStatus(BtcBlockSyncRecord.SyncStatus.SENT);
                record = syncRecordRepository.save(record);
            } else {
                record = recordOpt.get();
            }
            
            // 检查是否已处理
            if (record.getProcessStatus() == BtcBlockSyncRecord.ProcessStatus.COMPLETED) {
                LogUtil.debug(this.getClass(), "区块已处理，跳过: " + blockHeight);
                acknowledgment.acknowledge();
                return;
            }
            
            // 更新处理状态
            record.setProcessStatus(BtcBlockSyncRecord.ProcessStatus.PROCESSING);
            syncRecordRepository.save(record);
            
            // 解析区块数据
            JsonNode root = objectMapper.readTree(message);
            
            // 处理区块中的交易
            int processedCount = processBlockTransactions(root, blockHeight);
            
            // 更新处理完成状态
            record.setProcessStatus(BtcBlockSyncRecord.ProcessStatus.COMPLETED);
            record.setProcessedAt(LocalDateTime.now());
            syncRecordRepository.save(record);
            
            LogUtil.info(this.getClass(), "区块数据处理完成: 高度=" + blockHeight + ", 处理交易数=" + processedCount);
            
            // 确认消息
            acknowledgment.acknowledge();
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "处理区块数据失败: " + blockHeight, e);
            
            // 更新失败状态
            syncRecordRepository.findByBlockHeight(blockHeight).ifPresent(record -> {
                record.setProcessStatus(BtcBlockSyncRecord.ProcessStatus.FAILED);
                record.setErrorMessage(e.getMessage());
                record.setProcessedAt(LocalDateTime.now());
                syncRecordRepository.save(record);
            });
            
            // 确认消息（避免重复消费）
            acknowledgment.acknowledge();
        }
    }
    
    /**
     * 处理区块中的所有交易
     */
    private int processBlockTransactions(JsonNode blockNode, long blockHeight) {
        int processedCount = 0;
        
        // 兼容两种格式: "tx" (blockchain.info) 或 "txs" (mempool.space)
        String txFieldName = blockNode.has("txs") ? "txs" : "tx";
        if (!blockNode.has(txFieldName) || !blockNode.get(txFieldName).isArray()) {
            LogUtil.warn(this.getClass(), "区块数据中没有交易列表");
            return processedCount;
        }
        
        // 获取区块时间 - 兼容多种时间字段格式
        long timestamp = System.currentTimeMillis() / 1000;
        if (blockNode.has("time")) {
            timestamp = blockNode.get("time").asLong();
        } else if (blockNode.has("header") && blockNode.get("header").has("time")) {
            timestamp = blockNode.get("header").get("time").asLong();
        }
        // 从第一笔交易获取时间（mempool.space格式）
        JsonNode firstTx = blockNode.get(txFieldName).get(0);
        if (firstTx != null && firstTx.has("status") && firstTx.get("status").has("block_time")) {
            timestamp = firstTx.get("status").get("block_time").asLong();
        }
        
        LocalDateTime blockTime = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(timestamp),
            ZoneId.systemDefault()
        );
        
        // 获取热钱包地址
        List<BtcAddress> hotWalletList = addressRepository.findByAddressType(BtcAddress.AddressType.ROOT);
        String hotWalletAddress = hotWalletList.isEmpty() ? "" : hotWalletList.getFirst().getAddress();
        
        // 处理每笔交易
        for (JsonNode txNode : blockNode.get(txFieldName)) {
            // 兼容两种格式: "txid" (mempool.space) 或 "hash" (blockchain.info)
            String txHash = null;
            if (txNode.has("txid")) {
                txHash = txNode.get("txid").asText();
            } else if (txNode.has("hash")) {
                txHash = txNode.get("hash").asText();
            }
            
            if (txHash != null) {
                processTransaction(txHash, txNode, blockHeight, blockTime, hotWalletAddress);
                processedCount++;
            }
        }
        
        return processedCount;
    }
    
    /**
     * 处理单笔交易
     */
    private void processTransaction(String txHash, JsonNode txNode, long blockHeight, 
                                   LocalDateTime blockTime, String hotWalletAddress) {
        // 解析交易输入输出
        List<TransactionInput> inputs = parseInputs(txNode);
        List<TransactionOutput> outputs = parseOutputs(txNode);
        
        // 判断是否为归集交易
        boolean isCollectionTx = isCollectionTransaction(inputs, outputs, hotWalletAddress);
        
        // 处理交易输出（充值）
        for (TransactionOutput output : outputs) {
            processTransactionOutput(txHash, output, blockHeight, blockTime, isCollectionTx);
        }
        
        // 处理交易输入（UTXO花费/提现确认）
        for (TransactionInput input : inputs) {
            processTransactionInput(txHash, input, blockHeight, isCollectionTx, hotWalletAddress);
        }
    }
    
    /**
     * 解析交易输入
     * 兼容两种格式:
     * - mempool.space: "vin" 字段，包含 txid, vout, prevout
     * - blockchain.info: "inputs" 字段，包含 prev_out
     */
    private List<TransactionInput> parseInputs(JsonNode txNode) {
        List<TransactionInput> inputs = new ArrayList<>();
        
        // mempool.space 格式: vin
        if (txNode.has("vin") && txNode.get("vin").isArray()) {
            int index = 0;
            for (JsonNode vinNode : txNode.get("vin")) {
                String prevTxHash = "coinbase";
                int prevOutputIndex = 0;
                
                if (vinNode.has("txid")) {
                    prevTxHash = vinNode.get("txid").asText();
                }
                if (vinNode.has("vout")) {
                    prevOutputIndex = vinNode.get("vout").asInt();
                }
                
                inputs.add(new TransactionInput(prevTxHash, prevOutputIndex));
                index++;
            }
            return inputs;
        }
        
        // blockchain.info 格式: inputs
        if (txNode.has("inputs") && txNode.get("inputs").isArray()) {
            for (JsonNode inputNode : txNode.get("inputs")) {
                String prevTxHash = "coinbase";
                int prevOutputIndex = 0;
                
                if (inputNode.has("prev_out")) {
                    JsonNode prevOut = inputNode.get("prev_out");
                    if (prevOut.has("n")) {
                        prevOutputIndex = prevOut.get("n").asInt();
                    }
                    if (prevOut.has("tx_index")) {
                        prevTxHash = String.valueOf(prevOut.get("tx_index").asLong());
                    }
                }
                inputs.add(new TransactionInput(prevTxHash, prevOutputIndex));
            }
        }
        
        return inputs;
    }
    
    /**
     * 解析交易输出
     * 兼容两种格式:
     * - mempool.space: "vout" 字段，包含 n, scriptpubkey_address, value
     * - blockchain.info: "out" 字段，包含 n, addr, value
     */
    private List<TransactionOutput> parseOutputs(JsonNode txNode) {
        List<TransactionOutput> outputs = new ArrayList<>();
        
        // mempool.space 格式: vout
        if (txNode.has("vout") && txNode.get("vout").isArray()) {
            for (JsonNode voutNode : txNode.get("vout")) {
                int index = voutNode.has("n") ? voutNode.get("n").asInt() : 0;
                String address = "";
                // mempool.space 使用 scriptpubkey_address
                if (voutNode.has("scriptpubkey_address")) {
                    address = voutNode.get("scriptpubkey_address").asText();
                }
                long amount = voutNode.has("value") ? voutNode.get("value").asLong() : 0L;
                outputs.add(new TransactionOutput(index, address, amount));
            }
            return outputs;
        }
        
        // blockchain.info 格式: out
        if (txNode.has("out") && txNode.get("out").isArray()) {
            for (JsonNode outputNode : txNode.get("out")) {
                int index = outputNode.has("n") ? outputNode.get("n").asInt() : 0;
                String address = outputNode.has("addr") ? outputNode.get("addr").asText() : "";
                long amount = outputNode.has("value") ? outputNode.get("value").asLong() : 0L;
                outputs.add(new TransactionOutput(index, address, amount));
            }
        }
        
        return outputs;
    }
    
    /**
     * 判断是否为内部归集交易
     */
    private boolean isCollectionTransaction(List<TransactionInput> inputs, 
                                           List<TransactionOutput> outputs,
                                           String hotWalletAddress) {
        if (hotWalletAddress == null || hotWalletAddress.isEmpty()) {
            return false;
        }
        
        // 获取所有输出地址
        Set<String> outputAddresses = outputs.stream()
            .map(TransactionOutput::address)
            .filter(addr -> addr != null && !addr.isEmpty())
            .collect(Collectors.toSet());
        
        // 归集交易：输出只有一个地址且是热钱包
        if (outputAddresses.size() != 1 || !outputAddresses.contains(hotWalletAddress)) {
            return false;
        }
        
        // 检查输入是否来自用户地址
        for (TransactionInput input : inputs) {
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
     */
    private void processTransactionOutput(String txHash, TransactionOutput output,
                                         long blockHeight, LocalDateTime blockTime,
                                         boolean isCollectionTx) {
        Optional<BtcAddress> addressOpt = addressRepository.findByAddress(output.address());
        
        if (addressOpt.isEmpty()) {
            return;
        }
        
        BtcAddress address = addressOpt.get();
        
        // 幂等性检查
        if (depositRepository.findByTxHashAndOutputIndex(txHash, output.index()).isPresent()) {
            return;
        }
        
        if (utxoRepository.findByTxHashAndOutputIndex(txHash, output.index()).isPresent()) {
            return;
        }
        
        // 创建UTXO
        BtcUtxo utxo = new BtcUtxo();
        utxo.setTxHash(txHash);
        utxo.setOutputIndex(output.index());
        utxo.setAddress(output.address());
        utxo.setAmount(output.amount());
        utxo.setStatus(BtcUtxo.UtxoStatus.UNSPENT);
        utxo.setCreatedAt(LocalDateTime.now());
        utxo.setUpdatedAt(LocalDateTime.now());
        utxoRepository.save(utxo);
        
        // 归集交易输出到热钱包不创建充值记录
        if (isCollectionTx && address.getAddressType() == BtcAddress.AddressType.ROOT) {
            LogUtil.info(this.getClass(), "归集交易输出到热钱包: " + txHash + ":" + output.index());
            return;
        }
        
        // 创建充值记录
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
            ", 金额: " + output.amount() + ", 地址: " + output.address());
    }
    
    /**
     * 处理交易输入（UTXO花费/提现确认）
     */
    private void processTransactionInput(String txHash, TransactionInput input,
                                        long blockHeight, boolean isCollectionTx,
                                        String hotWalletAddress) {
        Optional<BtcUtxo> utxoOpt = utxoRepository.findByTxHashAndOutputIndex(
            input.prevTxHash(), input.prevOutputIndex());
        
        if (utxoOpt.isEmpty()) {
            return;
        }
        
        BtcUtxo utxo = utxoOpt.get();
        
        if (utxo.getStatus() == BtcUtxo.UtxoStatus.SPENT) {
            return;
        }
        
        // 标记UTXO为已花费
        utxo.setStatus(BtcUtxo.UtxoStatus.SPENT);
        utxo.setSpentTxHash(txHash);
        utxo.setUpdatedAt(LocalDateTime.now());
        utxoRepository.save(utxo);
        
        if (isCollectionTx) {
            LogUtil.info(this.getClass(), "归集交易花费UTXO: " + input.prevTxHash() + ":" + input.prevOutputIndex());
        } else {
            // 检查是否为提现交易
            Optional<BtcWithdrawal> withdrawalOpt = withdrawalRepository.findByTxHash(txHash);
            if (withdrawalOpt.isPresent()) {
                BtcWithdrawal withdrawal = withdrawalOpt.get();
                withdrawal.setBlockHeight(blockHeight);
                withdrawal.setConfirmations(1);
                withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.CONFIRMED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);
                
                LogUtil.info(this.getClass(), "提现确认: " + txHash);
            } else {
                // 非归集、非提现，扣减余额
                addressRepository.findByAddress(utxo.getAddress())
                    .ifPresent(addr -> {
                        addr.setBalance(addr.getBalance() - utxo.getAmount());
                        addressRepository.save(addr);
                    });
            }
        }
    }
    
    // 内部记录类
    private record TransactionInput(String prevTxHash, int prevOutputIndex) {}
    private record TransactionOutput(int index, String address, long amount) {}
}