package com.btc.repository;

import com.btc.entity.BtcAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtcAddressRepository extends JpaRepository<BtcAddress, Long> {
    
    /**
     * 根据地址查找
     */
    Optional<BtcAddress> findByAddress(String address);
    
    /**
     * 根据用户ID和地址类型查找用户充值地址
     */
    List<BtcAddress> findByUserIdAndAddressType(Long userId, BtcAddress.AddressType addressType);
    
    /**
     * 查找所有热钱包地址
     */
    List<BtcAddress> findByAddressType(BtcAddress.AddressType addressType);
    
    /**
     * 查找激活的地址
     */
    List<BtcAddress> findByActiveTrue();
    
    /**
     * 根据派生路径查找地址
     */
    Optional<BtcAddress> findByDerivationPath(String derivationPath);
    
    /**
     * 统计各类地址数量
     */
    @Query("SELECT COUNT(a) FROM BtcAddress a WHERE a.addressType = :type")
    Long countByAddressType(@Param("type") BtcAddress.AddressType type);
    
    /**
     * 查找余额大于0的地址
     */
    @Query("SELECT a FROM BtcAddress a WHERE a.balance > 0 AND a.active = true ORDER BY a.balance DESC")
    List<BtcAddress> findAddressesWithBalance();
    
    // ==================== 新增的BIP44路径查询方法 ====================
    
    /**
     * 根据用户ID查找所有地址，按地址索引降序排列
     */
    @Query("SELECT a FROM BtcAddress a WHERE a.userId = :userId ORDER BY a.addressIndex DESC")
    List<BtcAddress> findByUserIdOrderByAddressIndexDesc(@Param("userId") Long userId);
    
    /**
     * 根据coin_type、account和change_index查找所有地址，按地址索引降序排列
     */
    @Query("SELECT a FROM BtcAddress a WHERE a.coinType = :coinType AND a.account = :account AND a.changeIndex = :changeIndex ORDER BY a.addressIndex DESC")
    List<BtcAddress> findByCoinTypeAndAccountAndChangeIndexOrderByAddressIndexDesc(
            @Param("coinType") Integer coinType, 
            @Param("account") Long account, 
            @Param("changeIndex") Integer changeIndex);
    
    /**
     * 获取指定用户在指定业务链下的最大地址索引
     */
    @Query("SELECT MAX(a.addressIndex) FROM BtcAddress a WHERE a.coinType = :coinType AND a.account = :account AND a.changeIndex = :changeIndex")
    Optional<Integer> findMaxAddressIndexByCoinTypeAndAccountAndChangeIndex(
            @Param("coinType") Integer coinType, 
            @Param("account") Long account, 
            @Param("changeIndex") Integer changeIndex);
    
    /**
     * 获取指定用户在指定业务链下的最新地址（index最大的地址）
     */
    @Query("SELECT a FROM BtcAddress a WHERE a.coinType = :coinType AND a.account = :account AND a.changeIndex = :changeIndex ORDER BY a.addressIndex DESC LIMIT 1")
    Optional<BtcAddress> findLatestByCoinTypeAndAccountAndChangeIndex(
            @Param("coinType") Integer coinType, 
            @Param("account") Long account, 
            @Param("changeIndex") Integer changeIndex);
    
    /**
     * 根据完整路径参数查找地址
     */
    @Query("SELECT a FROM BtcAddress a WHERE a.coinType = :coinType AND a.account = :account AND a.changeIndex = :changeIndex AND a.addressIndex = :addressIndex")
    Optional<BtcAddress> findByFullPath(
            @Param("coinType") Integer coinType, 
            @Param("account") Long account, 
            @Param("changeIndex") Integer changeIndex, 
            @Param("addressIndex") Integer addressIndex);
}