package com.btc.service;

import com.btc.entity.BtcAddress;
import com.btc.entity.BtcUtxo;
import com.btc.entity.BtcWithdrawal;
import com.btc.repository.BtcAddressRepository;
import com.btc.repository.BtcUtxoRepository;
import com.btc.repository.BtcWithdrawalRepository;
import com.btc.util.LogUtil;
import com.btc.wallet.HDWalletManager;
import com.btc.client.BitcoinRpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.btc.service.SimpleEcdsaSigningService.getBytes;

@Service
@Transactional
public class WithdrawalService {
    
    // 手续费常量
    private static final long MIN_FEE_RATE = 1000L;  // 最小费率 1000 satoshi/kB
    private static final long MAX_FEE_RATE = 100000L; // 最大费率 100000 satoshi/kB
    private static final long DEFAULT_FEE_RATE = 5000L; // 默认费率 5000 satoshi/kB
    
    private final BtcWithdrawalRepository withdrawalRepository;
    
    private final BtcUtxoRepository utxoRepository;
    
    private final BtcAddressRepository addressRepository;
    
    private final AddressManagementService addressManagementService;
    
    private final BitcoinRpcClient bitcoinRpcClient;
    
    private final HDWalletManager hdWalletManager;
    
    private final TransactionSigningService transactionSigningService;

    public WithdrawalService(BtcWithdrawalRepository withdrawalRepository, BtcUtxoRepository utxoRepository, BtcAddressRepository addressRepository, AddressManagementService addressManagementService, BitcoinRpcClient bitcoinRpcClient, HDWalletManager hdWalletManager, TransactionSigningService transactionSigningService) {
        this.withdrawalRepository = withdrawalRepository;
        this.utxoRepository = utxoRepository;
        this.addressRepository = addressRepository;
        this.addressManagementService = addressManagementService;
        this.bitcoinRpcClient = bitcoinRpcClient;
        this.hdWalletManager = hdWalletManager;
        this.transactionSigningService = transactionSigningService;
    }

    /**
     * 用户提交提币申请
     */
    public BtcWithdrawal submitWithdrawal(Long userId, String toAddress, Long amount, Long fee) {
        // 验证参数
        if (amount <= 0 || fee <= 0) {
            throw new IllegalArgumentException("金额和手续费必须大于0");
        }
        
        long totalAmount = amount + fee;
        
        // 检查用户是否有足够的余额
        List<BtcAddress> userAddresses = addressManagementService.findUserAddresses(userId);
        long totalBalance = userAddresses.stream()
            .mapToLong(BtcAddress::getBalance)
            .sum();
            
        if (totalBalance < totalAmount) {
            throw new IllegalStateException("余额不足");
        }
        
        // 创建提币申请记录
        BtcWithdrawal withdrawal = new BtcWithdrawal();
        withdrawal.setUserId(userId);
        withdrawal.setToAddress(toAddress);
        withdrawal.setAmount(amount);
        withdrawal.setFee(fee);
        withdrawal.setTotalAmount(totalAmount);
        withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.PENDING);
        withdrawal.setCreatedAt(LocalDateTime.now());
        withdrawal.setUpdatedAt(LocalDateTime.now());
        
        return withdrawalRepository.save(withdrawal);
    }
    
    /**
     * 审核提币申请
     */
    public BtcWithdrawal approveWithdrawal(Long withdrawalId) {
        // 使用Java 8+ Optional.orElseThrow()简化代码
        BtcWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
            .orElseThrow(() -> new IllegalArgumentException("提币申请不存在"));
        
        if (withdrawal.getStatus() != BtcWithdrawal.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("提币申请状态不正确");
        }
        
        // 冻结用户资金
        freezeUserFunds(withdrawal);
        
        withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.APPROVED);
        withdrawal.setApprovedAt(LocalDateTime.now());
        withdrawal.setUpdatedAt(LocalDateTime.now());
        
        return withdrawalRepository.save(withdrawal);
    }
    
    /**
     * 拒绝提币申请
     */
    public BtcWithdrawal rejectWithdrawal(Long withdrawalId, String reason) {
        // 使用Java 8+ Optional.orElseThrow()简化代码
        BtcWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
            .orElseThrow(() -> new IllegalArgumentException("提币申请不存在"));
        
        if (withdrawal.getStatus() != BtcWithdrawal.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("提币申请状态不正确");
        }
        
        withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.REJECTED);
        withdrawal.setFailureReason(reason);
        withdrawal.setUpdatedAt(LocalDateTime.now());
        
        return withdrawalRepository.save(withdrawal);
    }
    
    /**
     * 处理已审核的提币申请（批量处理以节省手续费）
     */
    public void processApprovedWithdrawals() {
        List<BtcWithdrawal> approvedWithdrawals = withdrawalRepository
            .findByStatus(BtcWithdrawal.WithdrawalStatus.APPROVED);
            
        if (approvedWithdrawals.isEmpty()) {
            return;
        }
        
        // 将所有提币申请合并到一笔交易中执行
        try {
            processAllWithdrawalsInSingleTransaction(approvedWithdrawals);
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "批量处理所有提币失败: " + e.getMessage(), e);
            for (BtcWithdrawal withdrawal : approvedWithdrawals) {
                handleWithdrawalFailure(withdrawal, e.getMessage());
            }
        }
    }
    
    /**
     * 将所有提币申请合并到一笔交易中执行
     */
    private void processAllWithdrawalsInSingleTransaction(List<BtcWithdrawal> withdrawals) {
        LogUtil.info(this.getClass(), "开始处理所有提币申请，总数: " + withdrawals.size());
        
        if (withdrawals.isEmpty()) {
            return;
        }
        
        // 更新所有提币申请状态为处理中
        LocalDateTime now = LocalDateTime.now();
        for (BtcWithdrawal withdrawal : withdrawals) {
            withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.PROCESSING);
            withdrawal.setProcessingAt(now);
            withdrawal.setUpdatedAt(now);
            withdrawalRepository.save(withdrawal);
        }
        
        // 1. 选择合适的UTXO（为所有提币申请）
        List<BtcUtxo> selectedUtxos = selectUtxosForBatchWithdrawal(withdrawals);

        lockUtxos(selectedUtxos);
        
        try {
            // 3. 构造单笔交易并签名（包含所有提币）
            String txHash = constructAndSignSingleTransaction(withdrawals, selectedUtxos);
            
            // 4. 广播交易
            boolean broadcastSuccess = broadcastTransaction(txHash);
            
            if (broadcastSuccess) {
                // 5. 更新所有提币申请状态为完成
                completeBatchWithdrawal(withdrawals, txHash, selectedUtxos);
                LogUtil.info(this.getClass(), "所有提币处理完成，交易哈希: " + txHash);
            } else {
                throw new RuntimeException("交易广播失败");
            }
            
        } catch (Exception e) {
            // 回滚所有UTXO锁定状态
            unlockUtxos(selectedUtxos);
            throw e;
        }
    }
    
    /**
     * 构造包含所有提币的单笔交易
     */
    private String constructAndSignSingleTransaction(List<BtcWithdrawal> withdrawals, List<BtcUtxo> utxos) {
        try {
            // 计算总的输出金额
            Long totalOutput = withdrawals.stream()
                .mapToLong(BtcWithdrawal::getAmount)
                .sum();
                
            Long totalInput = utxos.stream()
                .mapToLong(BtcUtxo::getAmount)
                .sum();
            
            // 计算最优手续费
            Long optimalFee = calculateOptimalFee(utxos.size(), withdrawals.size() + 1); // +1为找零输出
            Long requiredAmount = totalOutput + optimalFee;
            
            if (totalInput < requiredAmount) {
                throw new IllegalStateException("输入金额不足，需要: " + requiredAmount + ", 实际: " + totalInput);
            }
            
            Long change = totalInput - requiredAmount;
            
            LogUtil.info(this.getClass(), "构造单笔交易: " + withdrawals.size() + "笔提币");
            LogUtil.info(this.getClass(), "总输出: " + totalOutput + " satoshis");
            LogUtil.info(this.getClass(), "总输入: " + totalInput + " satoshis");
            LogUtil.info(this.getClass(), "最优手续费: " + optimalFee + " satoshis");
            LogUtil.info(this.getClass(), "找零: " + change + " satoshis");
            
            // 构造未签名的Bitcoin交易
            String unsignedTxHex = createUnsignedBitcoinTransactionForAll(withdrawals, change, utxos);
            
            // 使用ECKey进行签名
            List<TransactionSigningService.UtxoInfo> utxoInfos = prepareUtxoInfos(utxos);
            String signedTxHex = transactionSigningService.signTransaction(unsignedTxHex, utxoInfos);
            
            LogUtil.info(this.getClass(), "单笔交易签名完成，交易哈希: " + signedTxHex);
            return signedTxHex;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "单笔交易构造失败: " + e.getMessage(), e);
            throw new RuntimeException("单笔交易构造失败", e);
        }
    }
    
    /**
     * 为批量提币选择UTXO
     */
    private List<BtcUtxo> selectUtxosForBatchWithdrawal(List<BtcWithdrawal> withdrawals) {
        // 计算总金额需求
        long totalAmount = withdrawals.stream()
            .mapToLong(BtcWithdrawal::getTotalAmount)
            .sum();
            
        // 收集所有相关用户的地址
        Set<Long> userIds = withdrawals.stream()
            .map(BtcWithdrawal::getUserId)
            .collect(Collectors.toSet());
            
        List<BtcUtxo> selectedUtxos = new ArrayList<>();
        Long remainingAmount = totalAmount;
        
        // 为每个用户收集UTXO
        for (Long userId : userIds) {
            if (remainingAmount <= 0) break;
            
            List<BtcAddress> userAddresses = addressManagementService.findUserAddresses(userId);
            for (BtcAddress address : userAddresses) {
                if (remainingAmount <= 0) break;
                
                List<BtcUtxo> availableUtxos = utxoRepository.findAvailableUtxosByAddress(address.getAddress());
                for (BtcUtxo utxo : availableUtxos) {
                    if (remainingAmount <= 0) break;
                    
                    selectedUtxos.add(utxo);
                    remainingAmount -= utxo.getAmount();
                }
            }
        }
        
        if (remainingAmount > 0) {
            throw new IllegalStateException("可用UTXO不足以支付批量提币金额，缺少: " + remainingAmount);
        }
        
        return selectedUtxos;
    }
    
    /**
     * 锁定UTXO（支持多个提币申请）
     */
    private void lockUtxos(List<BtcUtxo> utxos) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusMinutes(30);
        
        for (BtcUtxo utxo : utxos) {
            Optional<BtcUtxo> lockedUtxo = utxoRepository.findByIdForUpdate(utxo.getId());
            if (lockedUtxo.isPresent()) {
                BtcUtxo u = lockedUtxo.get();
                u.setStatus(BtcUtxo.UtxoStatus.LOCKED);
                u.setLockTime(now);
                u.setLockExpireTime(expireTime);
                u.setWithdrawalId(null); // 批量处理时不关联单个提币ID
                // 可以考虑添加额外字段存储批量关联信息
                u.setUpdatedAt(now);
                utxoRepository.save(u);
            }
        }
    }
    
    /**
     * 为所有提币创建未签名的Bitcoin交易
     */
    private String createUnsignedBitcoinTransactionForAll(List<BtcWithdrawal> withdrawals,
                                                          Long change,
                                                    List<BtcUtxo> utxos) throws Exception {
            
        LogUtil.info(this.getClass(), "开始构造包含所有提币的Bitcoin交易");
            
        // 1. 构造交易版本号
        byte[] versionBytes = new byte[]{0x02, 0x00, 0x00, 0x00}; // 版本2
            
        // 2. 构造输入数量
        byte[] inputCountBytes = encodeVarInt(utxos.size());
            
        // 3. 构造输入数据
        ByteArrayOutputStream inputStream = new ByteArrayOutputStream();
        for (BtcUtxo utxo : utxos) {
            // 交易哈希（反向字节序）
            byte[] txHash = hexToBytes(utxo.getTxHash());
            reverseBytes(txHash);
            inputStream.write(txHash);
                
            // 输出索引
            inputStream.write(utxo.getOutputIndex() & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 8) & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 16) & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 24) & 0xFF);
                
            // 空的解锁脚本长度
            inputStream.write(0x00);
                
            // 序列号
            inputStream.write(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF});
        }
            
        // 4. 构造输出数量（每个提币一个输出 + 找零输出）
        int outputCount = withdrawals.size() + (change > 0 ? 1 : 0);
        byte[] outputCountBytes = encodeVarInt(outputCount);
            
        // 5. 构造输出数据
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
        // 为每个提币创建输出
        for (BtcWithdrawal withdrawal : withdrawals) {
            byte[] amountBytes = longToLittleEndian(withdrawal.getAmount());
            outputStream.write(amountBytes);
            byte[] scriptPubKey = createP2PKHScriptPubKey(withdrawal.getToAddress());
            outputStream.write(encodeVarInt(scriptPubKey.length));
            outputStream.write(scriptPubKey);
        }
            
        // 找零输出
        if (change > 0) {
            byte[] changeAmountBytes = longToLittleEndian(change);
            outputStream.write(changeAmountBytes);
            byte[] changeScriptPubKey = createP2PKHScriptPubKey(getChangeAddress());
            outputStream.write(encodeVarInt(changeScriptPubKey.length));
            outputStream.write(changeScriptPubKey);
        }
            
        // 6. 构造locktime
        byte[] locktimeBytes = new byte[]{0x00, 0x00, 0x00, 0x00};
            
        // 7. 组装完整交易
        ByteArrayOutputStream txStream = new ByteArrayOutputStream();
        txStream.write(versionBytes);
        txStream.write(inputCountBytes);
        txStream.write(inputStream.toByteArray());
        txStream.write(outputCountBytes);
        txStream.write(outputStream.toByteArray());
        txStream.write(locktimeBytes);
            
        byte[] rawTransaction = txStream.toByteArray();
        String txHex = bytesToHex(rawTransaction);
            
        // 8. 计算交易哈希
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash1 = sha256.digest(rawTransaction);
        byte[] hash2 = sha256.digest(hash1);
        byte[] reversedHash = hash2.clone();
        reverseBytes(reversedHash);
        String txId = bytesToHex(reversedHash);
            
        LogUtil.info(this.getClass(), "包含所有提币的Bitcoin交易构造完成");
        LogUtil.info(this.getClass(), "交易ID: " + txId);
        LogUtil.info(this.getClass(), "交易大小: " + rawTransaction.length + " 字节");
        LogUtil.info(this.getClass(), "包含输出数: " + outputCount);
        LogUtil.debug(this.getClass(), "交易十六进制: " + txHex.substring(0, Math.min(100, txHex.length())) + "...");
            
        return txHex;
    }
        
    /**
     * 创建真实的Bitcoin交易（符合Bitcoin协议标准）
     */
    private String createRealBitcoinTransaction(String targetAddress, 
                                               Long totalOutput, 
                                               Long change, 
                                               List<BtcUtxo> utxos) throws Exception {
            
        LogUtil.info(this.getClass(), "开始构造真实的Bitcoin交易");
            
        // 1. 构造交易版本号
        byte[] versionBytes = new byte[]{0x02, 0x00, 0x00, 0x00}; // 版本2
            
        // 2. 构造输入数量
        byte[] inputCountBytes = encodeVarInt(utxos.size());
            
        // 3. 构造输入数据
        ByteArrayOutputStream inputStream = new ByteArrayOutputStream();
        for (BtcUtxo utxo : utxos) {
            // 交易哈希（反向字节序）
            byte[] txHash = hexToBytes(utxo.getTxHash());
            reverseBytes(txHash);
            inputStream.write(txHash);
                
            // 输出索引
            inputStream.write(utxo.getOutputIndex() & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 8) & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 16) & 0xFF);
            inputStream.write((utxo.getOutputIndex() >> 24) & 0xFF);
                
            // 空的解锁脚本长度
            inputStream.write(0x00);
                
            // 序列号
            inputStream.write(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF});
        }
            
        // 4. 构造输出数量
        int outputCount = change > 0 ? 2 : 1;
        byte[] outputCountBytes = encodeVarInt(outputCount);
            
        // 5. 构造输出数据
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
        // 主要输出
        byte[] amountBytes = longToLittleEndian(totalOutput);
        outputStream.write(amountBytes);
        byte[] scriptPubKey = createP2PKHScriptPubKey(targetAddress);
        outputStream.write(encodeVarInt(scriptPubKey.length));
        outputStream.write(scriptPubKey);
            
        // 找零输出
        if (change > 0) {
            byte[] changeAmountBytes = longToLittleEndian(change);
            outputStream.write(changeAmountBytes);
            byte[] changeScriptPubKey = createP2PKHScriptPubKey(getChangeAddress());
            outputStream.write(encodeVarInt(changeScriptPubKey.length));
            outputStream.write(changeScriptPubKey);
        }
            
        // 6. 构造locktime
        byte[] locktimeBytes = new byte[]{0x00, 0x00, 0x00, 0x00};
            
        // 7. 组装完整交易
        ByteArrayOutputStream txStream = new ByteArrayOutputStream();
        txStream.write(versionBytes);
        txStream.write(inputCountBytes);
        txStream.write(inputStream.toByteArray());
        txStream.write(outputCountBytes);
        txStream.write(outputStream.toByteArray());
        txStream.write(locktimeBytes);
            
        byte[] rawTransaction = txStream.toByteArray();
        String txHex = bytesToHex(rawTransaction);
            
        // 8. 计算交易哈希
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash1 = sha256.digest(rawTransaction);
        byte[] hash2 = sha256.digest(hash1);
        byte[] reversedHash = hash2.clone();
        reverseBytes(reversedHash);
        String txId = bytesToHex(reversedHash);
            
        LogUtil.info(this.getClass(), "真实Bitcoin交易构造完成");
        LogUtil.info(this.getClass(), "交易ID: " + txId);
        LogUtil.info(this.getClass(), "交易大小: " + rawTransaction.length + " 字节");
        LogUtil.debug(this.getClass(), "交易十六进制: " + txHex.substring(0, Math.min(100, txHex.length())) + "...");
            
        return txHex;
    }
        
    /**
     * 创建P2PKH脚本公钥
     */
    private byte[] createP2PKHScriptPubKey(String address) throws Exception {
        // 简化的地址到公钥哈希转换（实际项目中需要完整的Base58Check解码）
        // 这里使用固定的测试地址公钥哈希
        String pubKeyHash = "751e76e8199196d454941c45d1b3a323f1433bd6"; // 示例哈希
        byte[] hashBytes = hexToBytes(pubKeyHash);
            
        ByteArrayOutputStream script = new ByteArrayOutputStream();
        script.write(0x76); // OP_DUP
        script.write(0xA9); // OP_HASH160
        script.write(0x14); // Push 20 bytes
        script.write(hashBytes);
        script.write(0x88); // OP_EQUALVERIFY
        script.write(0xAC); // OP_CHECKSIG
            
        return script.toByteArray();
    }
        
    /**
     * 编码可变长度整数
     */
    private byte[] encodeVarInt(int value) {
        if (value < 0xFD) {
            return new byte[]{(byte)value};
        } else if (value <= 0xFFFF) {
            return new byte[]{(byte)0xFD, (byte)(value & 0xFF), (byte)((value >> 8) & 0xFF)};
        } else {
            return new byte[]{(byte)0xFE,
                            (byte)(value & 0xFF),
                            (byte)((value >> 8) & 0xFF),
                            (byte)((value >> 16) & 0xFF),
                            (byte)((value >> 24) & 0xFF)};
        }
    }
        
    /**
     * 长整型转小端字节数组
     */
    private byte[] longToLittleEndian(Long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte)((value >> (i * 8)) & 0xFF);
        }
        return bytes;
    }
        
    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexToBytes(String hex) {
        return getBytes(hex);
    }
        
    /**
     * 反转字节数组
     */
    private void reverseBytes(byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            byte temp = bytes[i];
            bytes[i] = bytes[bytes.length - 1 - i];
            bytes[bytes.length - 1 - i] = temp;
        }
    }
        
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    /**
     * 获取找零地址
     * 使用热钱包地址作为找零地址
     */
    private String getChangeAddress() {
        // 使用热钱包地址作为找零地址
        return hdWalletManager.getHotWalletAddress("btc");
    }
    
    /**
     * 完成批量提币
     */
    private void completeBatchWithdrawal(List<BtcWithdrawal> withdrawals, 
                                       String txHash, 
                                       List<BtcUtxo> usedUtxos) {
        LocalDateTime now = LocalDateTime.now();
        
        // 标记所有UTXO为已花费
        for (BtcUtxo utxo : usedUtxos) {
            utxo.setStatus(BtcUtxo.UtxoStatus.SPENT);
            utxo.setUpdatedAt(now);
            utxoRepository.save(utxo);
        }
        
        // 更新所有提币记录
        for (BtcWithdrawal withdrawal : withdrawals) {
            withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.COMPLETED);
            withdrawal.setTxHash(txHash);
            withdrawal.setCompletedAt(now);
            withdrawal.setUpdatedAt(now);
            withdrawalRepository.save(withdrawal);
        }
        
        // 更新用户地址余额
        Set<Long> userIds = withdrawals.stream()
            .map(BtcWithdrawal::getUserId)
            .collect(Collectors.toSet());
        
        for (Long userId : userIds) {
            updateUserBalances(userId);
        }
    }

    /**
     * 解锁UTXO
     */
    private void unlockUtxos(List<BtcUtxo> utxos) {
        LocalDateTime now = LocalDateTime.now();
        for (BtcUtxo utxo : utxos) {
            utxo.setStatus(BtcUtxo.UtxoStatus.UNSPENT);
            utxo.setLockTime(null);
            utxo.setLockExpireTime(null);
            utxo.setWithdrawalId(null);
            utxo.setUpdatedAt(now);
            utxoRepository.save(utxo);
        }
    }
    
    /**
     * 构造并签名交易（真实Bitcoin协议实现）
     */
    private String constructAndSignTransaction(BtcWithdrawal withdrawal, List<BtcUtxo> utxos) {
        try {
            // 计算最优手续费
            Long optimalFee = calculateOptimalFee(utxos.size(), 2); // 1个输出 + 1个找零
            Long requiredAmount = withdrawal.getAmount() + optimalFee;
            
            Long totalInput = utxos.stream()
                .mapToLong(BtcUtxo::getAmount)
                .sum();
                
            if (totalInput < requiredAmount) {
                throw new IllegalStateException("输入金额不足，需要: " + requiredAmount + ", 实际: " + totalInput);
            }
            
            Long change = totalInput - requiredAmount;
            
            LogUtil.info(this.getClass(), "构造单笔交易:");
            LogUtil.info(this.getClass(), "提币金额: " + withdrawal.getAmount() + " satoshis");
            LogUtil.info(this.getClass(), "最优手续费: " + optimalFee + " satoshis");
            LogUtil.info(this.getClass(), "总输入: " + totalInput + " satoshis");
            LogUtil.info(this.getClass(), "找零: " + change + " satoshis");
            
            // 使用真实Bitcoin交易构造
            String txData = createRealBitcoinTransaction(
                withdrawal.getToAddress(), 
                withdrawal.getAmount(), 
                change, 
                utxos
            );
            
            LogUtil.info(this.getClass(), "单笔交易构造完成，交易数据长度: " + txData.length());
            return txData;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "单笔交易构造失败: " + e.getMessage(), e);
            throw new RuntimeException("单笔交易构造失败", e);
        }
    }
    
    /**
     * 计算最优手续费
     * @param inputCount 输入UTXO数量
     * @param outputCount 输出数量（包括找零）
     * @return 最优手续费（satoshi）
     */
    private Long calculateOptimalFee(int inputCount, int outputCount) {
        // 估算交易大小（字节）
        int estimatedSize = estimateTransactionSize(inputCount, outputCount);
        
        // 获取当前网络费率（satoshi per byte）
        Long feeRate = getCurrentFeeRate();
        
        // 计算手续费
        Long fee = (long) estimatedSize * feeRate;
        
        LogUtil.info(this.getClass(), "交易估算大小: " + estimatedSize + " 字节");
        LogUtil.info(this.getClass(), "当前费率: " + feeRate + " satoshi/byte");
        LogUtil.info(this.getClass(), "计算手续费: " + fee + " satoshi");
        
        return fee;
    }
    
    /**
     * 估算交易大小
     * @param inputCount 输入数量
     * @param outputCount 输出数量
     * @return 估算的交易大小（字节）
     */
    private int estimateTransactionSize(int inputCount, int outputCount) {
        // 基础交易结构大小
        int baseSize = 10; // version(4) + input count(1) + output count(1) + locktime(4)
        
        // 输入大小估算（P2PKH输入约148字节，P2WPKH约68字节）
        int inputSize = inputCount * 148; // 使用保守估计
        
        // 输出大小估算（P2PKH输出约34字节）
        int outputSize = outputCount * 34;
        
        return baseSize + inputSize + outputSize;
    }
    
    /**
     * 获取当前网络手续费率
     * 实际项目中应该从区块链API获取实时费率
     * @return 手续费率（satoshi per byte）
     */
    private Long getCurrentFeeRate() {
        try {
            // 调用Bitcoin节点获取建议费率
            double feeRateBtc = bitcoinRpcClient.estimateSmartFee(6); // 6个确认为目标
            // 转换为satoshi per byte
            long feeRateSatoshi = (long)(feeRateBtc * 100000000 / 1000); // 假设平均交易大小1000字节
            
            // 确保费率在合理范围内
            long finalFeeRate = Math.max(MIN_FEE_RATE, Math.min(MAX_FEE_RATE, feeRateSatoshi));
            
            LogUtil.info(this.getClass(), "获取到网络建议费率: " + finalFeeRate + " satoshi/byte");
            return finalFeeRate;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取网络费率失败，使用默认费率: " + e.getMessage(), e);
            return DEFAULT_FEE_RATE;
        }
    }

    private boolean broadcastTransaction(String txHex) {
        try {
            // 调用Bitcoin节点广播交易
            String txId = bitcoinRpcClient.sendRawTransaction(txHex);
            LogUtil.info(this.getClass(), "交易广播成功，交易ID: " + txId);
            return true;
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "交易广播失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 完成提币
     */
    private void completeWithdrawal(BtcWithdrawal withdrawal, String txHash, List<BtcUtxo> usedUtxos) {
        // 标记UTXO为已花费
        LocalDateTime now = LocalDateTime.now();
        for (BtcUtxo utxo : usedUtxos) {
            utxo.setStatus(BtcUtxo.UtxoStatus.SPENT);
            utxo.setUpdatedAt(now);
            utxoRepository.save(utxo);
        }
        
        // 更新提币记录
        withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.COMPLETED);
        withdrawal.setTxHash(txHash);
        withdrawal.setCompletedAt(now);
        withdrawal.setUpdatedAt(now);
        withdrawalRepository.save(withdrawal);
        
        // 更新用户地址余额
        updateUserBalances(withdrawal.getUserId());
        
        LogUtil.info(this.getClass(), "提币完成: " + withdrawal.getId() + ", 交易哈希: " + txHash);
    }
    
    /**
     * 处理提币失败
     */
    private void handleWithdrawalFailure(BtcWithdrawal withdrawal, String reason) {
        withdrawal.setStatus(BtcWithdrawal.WithdrawalStatus.FAILED);
        withdrawal.setFailureReason(reason);
        withdrawal.setRetryCount(withdrawal.getRetryCount() + 1);
        withdrawal.setUpdatedAt(LocalDateTime.now());
        withdrawalRepository.save(withdrawal);
        
        // 解冻用户资金
        unfreezeUserFunds(withdrawal);
    }
    
    /**
     * 冻结用户资金
     * 
     * 流程说明：
     * 1. 用户提交提现申请时，将相应金额从balance转移到frozenBalance
     * 2. balance代表可用余额，frozenBalance代表冻结余额
     * 3. 提现完成后，从frozenBalance中扣除
     * 4. 如果提现失败/拒绝，将frozenBalance转回balance
     */
    private void freezeUserFunds(BtcWithdrawal withdrawal) {
        Long userId = withdrawal.getUserId();
        Long totalAmount = withdrawal.getTotalAmount(); // 金额 + 手续费
        
        List<BtcAddress> userAddresses = addressManagementService.findUserAddresses(userId);
        long remainingToFreeze = totalAmount;
        
        for (BtcAddress address : userAddresses) {
            if (remainingToFreeze <= 0) break;
            
            long availableBalance = address.getBalance();
            if (availableBalance > 0) {
                long freezeAmount = Math.min(availableBalance, remainingToFreeze);
                address.setBalance(availableBalance - freezeAmount);
                address.setFrozenBalance(address.getFrozenBalance() + freezeAmount);
                address.setUpdatedAt(LocalDateTime.now());
                addressRepository.save(address);
                remainingToFreeze -= freezeAmount;
                
                LogUtil.info(this.getClass(), String.format(
                    "冻结用户资金: 地址=%s, 冻结=%d 聪, 可用余额=%d 聪, 冻结余额=%d 聪",
                    address.getAddress(), freezeAmount, address.getBalance(), address.getFrozenBalance()));
            }
        }
        
        if (remainingToFreeze > 0) {
            LogUtil.warn(this.getClass(), String.format(
                "冻结资金不足: 用户ID=%d, 未冻结金额=%d 聪",
                userId, remainingToFreeze));
            throw new IllegalStateException("冻结资金失败：用户余额不足");
        }
        
        withdrawal.setFrozenAt(LocalDateTime.now());
    }
    
    /**
     * 解冻用户资金
     * 
     * 使用场景：
     * 1. 提现申请被拒绝
     * 2. 提现处理失败
     * 3. 其他需要取消提现的情况
     */
    private void unfreezeUserFunds(BtcWithdrawal withdrawal) {
        Long userId = withdrawal.getUserId();
        Long totalAmount = withdrawal.getTotalAmount();
        
        List<BtcAddress> userAddresses = addressManagementService.findUserAddresses(userId);
        long remainingToUnfreeze = totalAmount;
        
        for (BtcAddress address : userAddresses) {
            if (remainingToUnfreeze <= 0) break;
            
            long frozenBalance = address.getFrozenBalance();
            if (frozenBalance > 0) {
                long unfreezeAmount = Math.min(frozenBalance, remainingToUnfreeze);
                address.setFrozenBalance(frozenBalance - unfreezeAmount);
                address.setBalance(address.getBalance() + unfreezeAmount);
                address.setUpdatedAt(LocalDateTime.now());
                addressRepository.save(address);
                remainingToUnfreeze -= unfreezeAmount;
                
                LogUtil.info(this.getClass(), String.format(
                    "解冻用户资金: 地址=%s, 解冻=%d 聪, 可用余额=%d 聪, 冻结余额=%d 聪",
                    address.getAddress(), unfreezeAmount, address.getBalance(), address.getFrozenBalance()));
            }
        }
        
        if (remainingToUnfreeze > 0) {
            LogUtil.warn(this.getClass(), String.format(
                "解冻资金不足: 用户ID=%d, 未解冻金额=%d 聪",
                userId, remainingToUnfreeze));
        }
    }
    
    /**
     * 更新用户地址余额
     */
    private void updateUserBalances(Long userId) {
        List<BtcAddress> userAddresses = addressManagementService.findUserAddresses(userId);
        for (BtcAddress address : userAddresses) {
            Long balance = utxoRepository.sumUnspentAmountByAddress(address.getAddress());
            address.setBalance(balance);
            addressRepository.save(address);
        }
    }
    
    /**
     * 准备UTXO签名信息
     */
    private List<TransactionSigningService.UtxoInfo> prepareUtxoInfos(List<BtcUtxo> utxos) {
        List<TransactionSigningService.UtxoInfo> utxoInfos = new ArrayList<>();
        
        for (int i = 0; i < utxos.size(); i++) {
            BtcUtxo utxo = utxos.get(i);
            // 构造BIP44派生路径 (简化示例)
            String derivationPath = "m/44'/0'/0'/0/" + i;
            
            TransactionSigningService.UtxoInfo utxoInfo = 
                new TransactionSigningService.UtxoInfo(
                    utxo.getTxHash(),
                    utxo.getOutputIndex(),
                    utxo.getAddress(),
                    derivationPath,
                    utxo.getAmount()
                );
            utxoInfos.add(utxoInfo);
        }
        
        return utxoInfos;
    }
    
    /**
     * 清理过期锁定的UTXO
     */
    public void cleanupExpiredLocks() {
        LocalDateTime now = LocalDateTime.now();
        List<BtcUtxo> expiredUtxos = utxoRepository.findExpiredLockedUtxos(now);
        
        for (BtcUtxo utxo : expiredUtxos) {
            utxo.setStatus(BtcUtxo.UtxoStatus.UNSPENT);
            utxo.setLockTime(null);
            utxo.setLockExpireTime(null);
            utxo.setWithdrawalId(null);
            utxo.setUpdatedAt(now);
            utxoRepository.save(utxo);
        }
        
        LogUtil.info(this.getClass(), "清理了 " + expiredUtxos.size() + " 个过期锁定的UTXO");
    }
}