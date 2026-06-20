package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletEscrow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 通用托管单据持久化。
 */
@Mapper
public interface WalletEscrowMapper {

    int insert(WalletEscrow escrow);

    WalletEscrow findById(@Param("id") long id);

    WalletEscrow findByBusinessRef(@Param("businessRef") String businessRef);

    int updateStatus(WalletEscrow escrow);
}
