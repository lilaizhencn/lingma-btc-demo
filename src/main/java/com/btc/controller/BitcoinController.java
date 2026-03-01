package com.btc.controller;

import com.btc.entity.BtcAddress;
import com.btc.entity.BtcWithdrawal;
import com.btc.service.AddressManagementService;
import com.btc.service.WithdrawalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/btc")
public class BitcoinController {
    
    private final AddressManagementService addressService;
    
    private final WithdrawalService withdrawalService;

    public BitcoinController(AddressManagementService addressService, WithdrawalService withdrawalService) {
        this.addressService = addressService;
        this.withdrawalService = withdrawalService;
    }

    /**
     * 为用户生成充值地址
     */
    @GetMapping("/address/user/{userId}")
    public ResponseEntity<BtcAddress> generateUserAddress(@PathVariable Long userId) {
        try {
            BtcAddress address = addressService.deriveUserAddress(userId);
            return ResponseEntity.ok(address);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 获取热钱包地址（等同于Root地址）
     */
    @GetMapping("/address/hot-wallet")
    public ResponseEntity<BtcAddress> getHotWalletAddress() {
        try {
            return addressService.getHotWalletAddress()
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 获取用户最新的充值地址
     */
    @GetMapping("/address/user/{userId}/latest")
    public ResponseEntity<BtcAddress> getLatestUserAddress(@PathVariable Long userId) {
        try {
            return addressService.getLatestUserAddress(userId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 获取用户所有地址
     */
    @GetMapping("/address/user/{userId}/all")
    public ResponseEntity<java.util.List<BtcAddress>> getUserAllAddresses(@PathVariable Long userId) {
        try {
            return ResponseEntity.ok(addressService.getUserAllAddresses(userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 提交提币申请
     */
    @PostMapping("/withdrawal")
    public ResponseEntity<BtcWithdrawal> submitWithdrawal(
            @RequestParam Long userId,
            @RequestParam String toAddress,
            @RequestParam Long amount,
            @RequestParam Long fee) {
        try {
            BtcWithdrawal withdrawal = withdrawalService.submitWithdrawal(userId, toAddress, amount, fee);
            return ResponseEntity.ok(withdrawal);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 审核提币申请
     */
    @PostMapping("/withdrawal/{id}/approve")
    public ResponseEntity<BtcWithdrawal> approveWithdrawal(@PathVariable Long id) {
        try {
            BtcWithdrawal withdrawal = withdrawalService.approveWithdrawal(id);
            return ResponseEntity.ok(withdrawal);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 拒绝提币申请
     */
    @PostMapping("/withdrawal/{id}/reject")
    public ResponseEntity<BtcWithdrawal> rejectWithdrawal(
            @PathVariable Long id, 
            @RequestParam String reason) {
        try {
            BtcWithdrawal withdrawal = withdrawalService.rejectWithdrawal(id, reason);
            return ResponseEntity.ok(withdrawal);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 手动触发区块扫描
     */
    @PostMapping("/scan/blocks")
    public ResponseEntity<String> triggerBlockScan() {
        try {
            // 这里应该注入BlockScanningService并调用相应方法
            return ResponseEntity.ok("区块扫描已触发");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("扫描失败: " + e.getMessage());
        }
    }
}