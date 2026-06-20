package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 钱包账户余额快照持久化。
 */
@Mapper
public interface WalletAccountMapper {

    WalletAccount findByOwnerUserId(@Param("ownerUserId") long ownerUserId);

    int insert(WalletAccount account);

    int updateBalancesAndStatus(WalletAccount account);
}
