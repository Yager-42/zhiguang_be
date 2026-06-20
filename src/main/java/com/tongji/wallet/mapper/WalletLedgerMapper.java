package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletLedgerEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 只追加钱包流水持久化。
 */
@Mapper
public interface WalletLedgerMapper {

    int insert(WalletLedgerEntry entry);

    WalletLedgerEntry findByBusinessRef(@Param("businessRef") String businessRef);

    List<WalletLedgerEntry> listByOwnerUserId(@Param("ownerUserId") long ownerUserId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);
}
