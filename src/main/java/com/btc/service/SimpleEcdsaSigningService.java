package com.btc.service;

import com.btc.util.LogUtil;
import com.btc.wallet.HDWalletManager;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 简化版ECKey签名服务
 * 实现基本的ECDSA签名功能
 */
@Service
public class SimpleEcdsaSigningService {

    private final HDWalletManager hdWalletManager;

    public SimpleEcdsaSigningService(HDWalletManager hdWalletManager) {
        this.hdWalletManager = hdWalletManager;
    }

    /**
     * 使用ECKey对数据进行签名
     * @param dataToSign 待签名的数据
     * @param derivationPath BIP44派生路径
     * @return DER编码的签名
     */
    public String signData(String dataToSign, String derivationPath) {
        try {
            LogUtil.info(this.getClass(), "开始ECKey签名，路径: " + derivationPath);
            
            // 1. 派生私钥
            ECKey signingKey = derivePrivateKey(derivationPath);
            
            // 2. 计算数据哈希
            byte[] dataBytes = dataToSign.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(dataBytes);
            
            // 3. 使用ECKey签名
            Sha256Hash sha256Hash = Sha256Hash.wrap(hash);
            ECKey.ECDSASignature signature = signingKey.sign(sha256Hash);
            
            // 4. 编码为DER格式
            byte[] derSignature = signature.encodeToDER();
            
            String signatureHex = bytesToHex(derSignature);
            LogUtil.info(this.getClass(), "签名完成，签名长度: " + signatureHex.length());
            
            return signatureHex;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "ECKey签名失败: " + e.getMessage(), e);
            throw new RuntimeException("ECKey签名失败", e);
        }
    }

    /**
     * 验证ECDSA签名
     * @param data 原始数据
     * @param signature DER编码的签名
     * @param publicKeyHex 公钥十六进制
     * @return 签名是否有效
     */
    public boolean verifySignature(String data, String signature, String publicKeyHex) {
        try {
            // 1. 计算数据哈希
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(dataBytes);
            
            // 2. 解析签名
            byte[] signatureBytes = hexToBytes(signature);
            ECKey.ECDSASignature ecdsaSignature = ECKey.ECDSASignature.decodeFromDER(signatureBytes);
            
            // 3. 解析公钥
            byte[] publicKeyBytes = hexToBytes(publicKeyHex);
            ECKey publicKey = ECKey.fromPublicOnly(publicKeyBytes);
            
            // 4. 验证签名
            Sha256Hash sha256Hash = Sha256Hash.wrap(hash);
            return publicKey.verify(sha256Hash, ecdsaSignature);
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "签名验证失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 派生私钥
     */
    private ECKey derivePrivateKey(String derivationPath) {
        try {
            DeterministicKey rootKey = hdWalletManager.getRootKey();
            if (rootKey == null) {
                hdWalletManager.generateNewWallet();
                rootKey = hdWalletManager.getRootKey();
            }

            // 解析派生路径
            String[] parts = derivationPath.replace("m/", "").split("/");
            DeterministicKey currentKey = rootKey;
            
            for (String part : parts) {
                int index;
                if (part.endsWith("'")) {
                    // Hardened derivation
                    index = Integer.parseInt(part.replace("'", "")) | 0x80000000;
                } else {
                    // Normal derivation
                    index = Integer.parseInt(part);
                }
                currentKey = HDKeyDerivation.deriveChildKey(currentKey, index);
            }

            // 转换为ECKey
            BigInteger privateKey = currentKey.getPrivKey();
            return ECKey.fromPrivate(privateKey);

        } catch (Exception e) {
            LogUtil.error(this.getClass(), "私钥派生失败: " + e.getMessage(), e);
            throw new RuntimeException("私钥派生失败", e);
        }
    }

    /**
     * 获取公钥
     */
    public String getPublicKey(String derivationPath) {
        try {
            ECKey key = derivePrivateKey(derivationPath);
            return bytesToHex(key.getPubKey());
        } catch (Exception e) {
            throw new RuntimeException("获取公钥失败", e);
        }
    }

    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexToBytes(String hex) {
        return getBytes(hex);
    }

    static byte[] getBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
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
}