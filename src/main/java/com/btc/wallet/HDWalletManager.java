package com.btc.wallet;

import com.btc.entity.BtcWalletSeed;
import com.btc.repository.BtcWalletSeedRepository;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * HD钱包管理器 - 实现BIP32/BIP44标准
 * 生产环境实现：种子加密存储到数据库
 */
@Component
public class HDWalletManager {
    
    @Autowired
    private BtcWalletSeedRepository walletSeedRepository;
    
    private String encryptionPassword = "default_wallet_password";  // 默认加密密码
    
    private String network = "testnet";  // 默认测试网
    
    private byte[] originalSeed;  // 保存原始种子，用于持久化
    private DeterministicKey rootKey;
    private NetworkParameters networkParams;
    private SecretKey encryptionKey;
    
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final String DEFAULT_WALLET_ID = "primary_wallet";
    
    /**
     * 初始化网络参数
     */
    private void initializeNetwork() {
        if (network == null) {
            network = "testnet";
        }
        
        switch (network.toLowerCase()) {
            case "mainnet":
                this.networkParams = MainNetParams.get();
                break;
            case "testnet":
            default:
                this.networkParams = TestNet3Params.get();
                break;
        }
    }
    
    /**
     * 初始化加密密钥
     */
    private void initializeEncryptionKey() {
        try {
            if (encryptionPassword == null) {
                encryptionPassword = "default_wallet_password";
            }
            
            byte[] passwordBytes = encryptionPassword.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(passwordBytes);
            this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("初始化加密密钥失败", e);
        }
    }
    
    /**
     * 生成新的HD钱包根私钥
     * 使用256位随机seed + HMAC-SHA512
     */
    public void generateNewWallet() {
        try {
            if (encryptionKey == null) {
                initializeEncryptionKey();
            }
            
            if (networkParams == null) {
                initializeNetwork();
            }
            
            // 生成256位随机种子
            SecureRandom secureRandom = new SecureRandom();
            byte[] seed = new byte[32];
            secureRandom.nextBytes(seed);
            
            // 保存原始种子
            this.originalSeed = seed.clone();
            
            // 使用HMAC-SHA512生成根私钥
            this.rootKey = HDKeyDerivation.createMasterPrivateKey(seed);
            
            System.out.println("✅ 新HD钱包生成成功");
            
        } catch (Exception e) {
            throw new RuntimeException("生成HD钱包失败", e);
        }
    }
    
    /**
     * 从加密存储加载钱包
     */
    public boolean loadWalletFromStorage() {
        try {
            if (encryptionKey == null) {
                initializeEncryptionKey();
            }
            
            if (networkParams == null) {
                initializeNetwork();
            }
            
            // 从数据库加载加密的种子数据
            String encryptedSeed = loadEncryptedSeedFromStorage();
            if (encryptedSeed != null) {
                byte[] seed = decryptSeed(encryptedSeed);
                // 保存原始种子
                this.originalSeed = seed.clone();
                // 使用种子重建根私钥
                this.rootKey = HDKeyDerivation.createMasterPrivateKey(seed);
                System.out.println("✅ 从数据库加载钱包成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("加载钱包失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 通用HD钱包地址派生方法
     * BIP44路径: m/44'/coin_type'/account'/change/address_index
     */
    public String deriveAddress(int coinType, int account, int change, int addressIndex) {
        if (rootKey == null) {
            throw new IllegalStateException("钱包未初始化");
        }
        
        DeterministicKey purposeKey = HDKeyDerivation.deriveChildKey(rootKey, 44 | 0x80000000);
        DeterministicKey coinTypeKey = HDKeyDerivation.deriveChildKey(purposeKey, coinType | 0x80000000);
        DeterministicKey accountKey = HDKeyDerivation.deriveChildKey(coinTypeKey, account | 0x80000000);
        DeterministicKey changeKey = HDKeyDerivation.deriveChildKey(accountKey, change);
        DeterministicKey addressKey = HDKeyDerivation.deriveChildKey(changeKey, addressIndex);
        
        return encodeBech32Address(addressKey.getPubKeyHash(), getHrpPrefix());
    }
    
    /**
     * 派生Root地址
     * 路径: m/44'/coin_type'/0'/0/0
     */
    public String deriveRootAddress(int coinType) {
        return deriveAddress(coinType, 0, 0, 0);
    }
    
    /**
     * 通过指定的rootKey派生Root地址
     * 路径: m/44'/coin_type'/0'/0/0
     */
    public String deriveRootAddress(DeterministicKey rootKey, int coinType) {
        if (rootKey == null) {
            throw new IllegalArgumentException("rootKey不能为null");
        }
        
        DeterministicKey purposeKey = HDKeyDerivation.deriveChildKey(rootKey, 44 | 0x80000000);
        DeterministicKey coinTypeKey = HDKeyDerivation.deriveChildKey(purposeKey, coinType | 0x80000000);
        DeterministicKey accountKey = HDKeyDerivation.deriveChildKey(coinTypeKey, 0 | 0x80000000);
        DeterministicKey changeKey = HDKeyDerivation.deriveChildKey(accountKey, 0);
        DeterministicKey addressKey = HDKeyDerivation.deriveChildKey(changeKey, 0);
        
        return encodeBech32Address(addressKey.getPubKeyHash(), getHrpPrefix());
    }
    
    /**
     * 派生用户充值地址（兼容旧方法）
     * 路径: m/44'/coin_type'/account'/0/index
     */
    public String deriveUserDepositAddress(int account, int coinType, int addressIndex) {
        return deriveAddress(coinType, account, 0, addressIndex);
    }
    
    /**
     * 派生用户找零地址
     * 路径: m/44'/coin_type'/account'/1/index
     */
    public String deriveChangeAddress(int coinType, int account, int addressIndex) {
        return deriveAddress(coinType, account, 1, addressIndex);
    }
    
    /**
     * 获取热钱包地址（等同于Root地址）
     * 路径: m/44'/coin_type'/0'/0/0
     */
    public String getHotWalletAddress(String coinSymbol) {
        return deriveRootAddress(getCoinType(coinSymbol));
    }
    
    /**
     * 加密种子数据
     */
    private String encryptSeed(byte[] seed) {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);
            
            byte[] encryptedData = cipher.doFinal(seed);
            byte[] result = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);
            
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("种子加密失败", e);
        }
    }
    
    /**
     * 解密种子数据
     */
    private byte[] decryptSeed(String encryptedSeed) {
        try {
            byte[] data = Base64.getDecoder().decode(encryptedSeed);
            byte[] iv = Arrays.copyOfRange(data, 0, GCM_IV_LENGTH);
            byte[] encryptedData = Arrays.copyOfRange(data, GCM_IV_LENGTH, data.length);
            
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);
            
            return cipher.doFinal(encryptedData);
        } catch (Exception e) {
            throw new RuntimeException("种子解密失败", e);
        }
    }
    
    /**
     * 保存钱包到加密存储
     * 保存原始种子，而不是私钥字节
     */
    public void saveWalletToStorage() {
        if (originalSeed == null) {
            throw new IllegalStateException("钱包种子未初始化");
        }
        
        try {
            String encryptedSeed = encryptSeed(originalSeed);
            saveEncryptedSeedToStorage(encryptedSeed);
            System.out.println("✅ 钱包已加密保存");
        } catch (Exception e) {
            throw new RuntimeException("保存钱包失败", e);
        }
    }
    
    /**
     * 从数据库加载加密种子
     */
    private String loadEncryptedSeedFromStorage() {
        try {
            return walletSeedRepository.findByPrimaryTrueAndActiveTrueAndNetwork(network)
                    .map(BtcWalletSeed::getEncryptedSeed)
                    .orElse(null);
        } catch (Exception e) {
            System.err.println("从数据库加载钱包种子失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 保存加密种子到数据库
     */
    private void saveEncryptedSeedToStorage(String encryptedSeed) {
        try {
            BtcWalletSeed walletSeed = walletSeedRepository
                    .findByPrimaryTrueAndActiveTrueAndNetwork(network)
                    .orElse(new BtcWalletSeed());
            
            walletSeed.setWalletId(DEFAULT_WALLET_ID + "_" + network);
            walletSeed.setEncryptedSeed(encryptedSeed);
            walletSeed.setEncryptionIv("auto");
            walletSeed.setNetwork(network);
            walletSeed.setPrimary(true);
            walletSeed.setActive(true);
            
            walletSeedRepository.save(walletSeed);
            System.out.println("✅ 钱包种子已保存到数据库");
        } catch (Exception e) {
            System.err.println("保存钱包种子到数据库失败: " + e.getMessage());
            throw new RuntimeException("保存钱包种子失败", e);
        }
    }
    
    /**
     * 检查数据库中是否存在钱包
     */
    public boolean hasWalletInStorage() {
        try {
            return walletSeedRepository.existsByPrimaryTrueAndActiveTrue();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取币种对应的coin_type
     */
    public int getCoinType(String coinSymbol) {
        if (network == null) {
            network = "testnet";
        }
        
        switch (coinSymbol.toLowerCase()) {
            case "btc":
                return network.equals("mainnet") ? 0 : 1;
            case "ltc":
                return network.equals("mainnet") ? 2 : 1;
            case "dash":
                return network.equals("mainnet") ? 5 : 1;
            case "doge":
                return network.equals("mainnet") ? 3 : 1;
            default:
                throw new IllegalArgumentException("不支持的币种: " + coinSymbol);
        }
    }
    
    /**
     * 获取HRP前缀
     */
    private String getHrpPrefix() {
        return getHrpPrefix("btc");
    }
    
    /**
     * 获取指定币种的HRP前缀
     */
    private String getHrpPrefix(String coinSymbol) {
        if (network == null) {
            network = "testnet";
        }
        
        boolean isMainnet = network.equals("mainnet");
        
        switch (coinSymbol.toLowerCase()) {
            case "btc":
                return isMainnet ? "bc" : "tb";
            case "ltc":
                return isMainnet ? "lt" : "tlt";
            case "dash":
                return isMainnet ? "dash" : "tdash";
            case "doge":
                return isMainnet ? "doge" : "tdge";
            default:
                return isMainnet ? "bc" : "tb";
        }
    }
    
    /**
     * Bech32编码实现
     */
    private String encodeBech32Address(byte[] pubkeyHash, String hrp) {
        try {
            byte[] witnessProgram = new byte[pubkeyHash.length + 2];
            witnessProgram[0] = 0x00;
            witnessProgram[1] = (byte) pubkeyHash.length;
            System.arraycopy(pubkeyHash, 0, witnessProgram, 2, pubkeyHash.length);
            
            return bech32Encode(hrp, witnessProgram);
        } catch (Exception e) {
            throw new RuntimeException("Bech32编码失败", e);
        }
    }
    
    /**
     * Bech32编码核心实现
     */
    private String bech32Encode(String hrp, byte[] data) {
        char[] charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l".toCharArray();
        
        int[] data5bit = convertTo5BitArray(data);
        int[] checksum = createChecksum(hrp, data5bit);
        
        int[] combined = new int[data5bit.length + checksum.length];
        System.arraycopy(data5bit, 0, combined, 0, data5bit.length);
        System.arraycopy(checksum, 0, combined, data5bit.length, checksum.length);
        
        StringBuilder result = new StringBuilder();
        result.append(hrp).append("1");
        
        for (int value : combined) {
            result.append(charset[value]);
        }
        
        return result.toString();
    }
    
    /**
     * 将8-bit数据转换为5-bit数组
     */
    private int[] convertTo5BitArray(byte[] data) {
        int[] result = new int[(data.length * 8 + 4) / 5];
        int buffer = 0;
        int bufferBits = 0;
        int index = 0;
        
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bufferBits += 8;
            
            while (bufferBits >= 5) {
                bufferBits -= 5;
                result[index++] = (buffer >> bufferBits) & 0x1F;
            }
        }
        
        if (bufferBits > 0) {
            result[index++] = (buffer << (5 - bufferBits)) & 0x1F;
        }
        
        return java.util.Arrays.copyOf(result, index);
    }
    
    /**
     * 创建Bech32校验和
     */
    private int[] createChecksum(String hrp, int[] data) {
        int[] values = new int[hrp.length() * 2 + 1 + data.length + 6];
        int index = 0;
        
        for (char c : hrp.toCharArray()) {
            values[index++] = c >> 5;
        }
        values[index++] = 0;
        for (char c : hrp.toCharArray()) {
            values[index++] = c & 0x1F;
        }
        
        for (int value : data) {
            values[index++] = value;
        }
        
        for (int i = 0; i < 6; i++) {
            values[index++] = 0;
        }
        
        int polymod = polymod(values) ^ 1;
        int[] checksum = new int[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (polymod >> (5 * (5 - i))) & 0x1F;
        }
        
        return checksum;
    }
    
    /**
     * Bech32多项式模运算
     */
    private int polymod(int[] values) {
        int[] generator = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        
        for (int value : values) {
            int top = chk >> 25;
            chk = (chk & 0x1ffffff) << 5 ^ value;
            for (int i = 0; i < 5; i++) {
                if (((top >> i) & 1) != 0) {
                    chk ^= generator[i];
                }
            }
        }
        return chk;
    }
    
    // Getter方法
    public boolean isInitialized() {
        return rootKey != null;
    }
    
    public String getNetwork() {
        return network;
    }
    
    public DeterministicKey getRootKey() {
        return rootKey;
    }
    
    public NetworkParameters getNetworkParameters() {
        return networkParams;
    }
}