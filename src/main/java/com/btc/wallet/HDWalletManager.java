package com.btc.wallet;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * HD钱包管理器 - 实现BIP32/BIP44标准
 * 简化版本：种子从配置文件读取
 */
@Component
public class HDWalletManager {
    
    @Value("${bitcoin.wallet.seed:}")
    private String configSeed;  // 从配置文件读取的种子（十六进制）
    
    @Value("${bitcoin.network:testnet}")
    private String network;  // 网络类型
    
    private DeterministicKey rootKey;
    private NetworkParameters networkParams;
    
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
     * 从配置文件加载钱包
     * @return 是否加载成功
     */
    public boolean loadWalletFromStorage() {
        try {
            if (networkParams == null) {
                initializeNetwork();
            }
            
            // 从配置文件读取种子
            if (configSeed != null && !configSeed.isEmpty()) {
                byte[] seed = hexToBytes(configSeed);
                this.rootKey = HDKeyDerivation.createMasterPrivateKey(seed);
                System.out.println("✅ 从配置文件加载钱包成功");
                return true;
            }
            
            System.err.println("配置文件中未找到钱包种子");
            return false;
        } catch (Exception e) {
            System.err.println("加载钱包失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 生成新的HD钱包根私钥
     * 使用256位随机seed + HMAC-SHA512
     */
    public void generateNewWallet() {
        try {
            if (networkParams == null) {
                initializeNetwork();
            }
            
            // 生成256位随机种子
            SecureRandom secureRandom = new SecureRandom();
            byte[] seed = new byte[32];
            secureRandom.nextBytes(seed);
            
            // 使用HMAC-SHA512生成根私钥
            this.rootKey = HDKeyDerivation.createMasterPrivateKey(seed);
            
            // 输出种子供配置使用
            System.out.println("✅ 新HD钱包生成成功");
            System.out.println("种子(Hex): " + bytesToHex(seed));
            System.out.println("请将种子配置到 application.yml 中: bitcoin.wallet.seed: " + bytesToHex(seed));
            
        } catch (Exception e) {
            throw new RuntimeException("生成HD钱包失败", e);
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

        return switch (coinSymbol.toLowerCase()) {
            case "ltc" -> isMainnet ? "lt" : "tlt";
            case "dash" -> isMainnet ? "dash" : "tdash";
            case "doge" -> isMainnet ? "doge" : "tdge";
            default -> isMainnet ? "bc" : "tb";
        };
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
        
        return Arrays.copyOf(result, index);
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
    
    /**
     * 通过DeterministicKey获取私钥（十六进制字符串）
     */
    public String getPrivateKeyHex(DeterministicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        byte[] privateKeyBytes = key.getPrivKeyBytes();
        if (privateKeyBytes == null) {
            throw new IllegalArgumentException("该key不包含私钥信息");
        }
        return bytesToHex(privateKeyBytes);
    }
    
    /**
     * 通过DeterministicKey获取私钥（WIF格式）
     */
    public String getPrivateKeyWIF(DeterministicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        if (networkParams == null) {
            initializeNetwork();
        }
        return key.getPrivateKeyAsWiF(networkParams);
    }
    
    /**
     * 通过DeterministicKey获取公钥（十六进制字符串）
     */
    public String getPublicKeyHex(DeterministicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        byte[] publicKeyBytes = key.getPubKey();
        return bytesToHex(publicKeyBytes);
    }
    
    /**
     * 通过DeterministicKey获取公钥（压缩格式）
     */
    public String getCompressedPublicKeyHex(DeterministicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        byte[] compressedPubKey = key.getPubKeyPoint().getEncoded(true);
        return bytesToHex(compressedPubKey);
    }
    
    /**
     * 通过DeterministicKey获取地址（Bech32/Native SegWit格式）
     */
    public String getAddress(DeterministicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        return encodeBech32Address(key.getPubKeyHash(), getHrpPrefix());
    }
    
    /**
     * 通过DeterministicKey获取地址（指定币种）
     */
    public String getAddress(DeterministicKey key, String coinSymbol) {
        if (key == null) {
            throw new IllegalArgumentException("DeterministicKey不能为null");
        }
        return encodeBech32Address(key.getPubKeyHash(), getHrpPrefix(coinSymbol));
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("十六进制字符串不能为空");
        }
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;  // 补齐
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
    
    /**
     * 测试主方法
     */
    public static void main(String[] args) {
        System.out.println("========== HDWalletManager 密钥测试 ==========\n");
        
        try {
            // 创建HDWalletManager实例
            HDWalletManager walletManager = new HDWalletManager();
            walletManager.initializeNetwork();
            
            // 测试用的种子（从配置文件读取）
            String testSeed = "30919cf1818b1ca23b46dd3a9df8503652d0bf046ded7dd6f57cfd6db2283699";
            walletManager.configSeed = testSeed;
            
            // 从配置加载钱包
            System.out.println("1. 从配置文件加载钱包...");
            walletManager.loadWalletFromStorage();
            
            DeterministicKey rootKey = walletManager.getRootKey();
            System.out.println("\n========== 根密钥信息 ==========");

            // 测试获取私钥
            System.out.println("\n2. 测试获取私钥:");
//            String privateKeyHex = walletManager.getPrivateKeyHex(rootKey);
            String privateKeyWIF = walletManager.getPrivateKeyWIF(rootKey);
//            System.out.println("   私钥(Hex): " + privateKeyHex);
            System.out.println("   私钥(WIF): " + privateKeyWIF);
            
            // 测试获取公钥
            System.out.println("\n3. 测试获取公钥:");
            String compressedPubKey = walletManager.getCompressedPublicKeyHex(rootKey);
            System.out.println("   公钥(Hex-压缩): " + compressedPubKey);
            
            // 测试获取地址
            System.out.println("\n4. 测试获取地址:");
            String address = walletManager.getAddress(rootKey);
            System.out.println("   根私钥地址(Bech32): " + address);
            
            // 测试派生地址
            System.out.println("\n========== 派生地址测试 ==========");
            String derivedAddress = walletManager.deriveRootAddress(1);  // BTC测试网 coin_type=1
            System.out.println("   派生地址 m/44'/1'/0'/0/0: " + derivedAddress);
            
            System.out.println("\n========== 测试完成 ==========");
            
        } catch (Exception e) {
            System.err.println("测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}