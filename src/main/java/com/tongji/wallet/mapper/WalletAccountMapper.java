package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 钱包账户余额快照持久化。
 * <p>
 * 余额变更必须走 {@link #applyBalanceDeltas} 的 delta 更新；禁止用读到的旧余额生成绝对新余额后覆盖。
 */
@Mapper
public interface WalletAccountMapper {

    WalletAccount findByOwnerUserId(@Param("ownerUserId") long ownerUserId);

    /** 行锁读取：mutating 操作在事务内先调它锁住账户行，再 delta 更新。 */
    WalletAccount findByOwnerUserIdForUpdate(@Param("ownerUserId") long ownerUserId);

    int insert(WalletAccount account);

    /**
     * 原子 delta 更新余额，带非负保护；返回受影响行数（0 表示余额不足或账户不存在）。
     */
    int applyBalanceDeltas(@Param("ownerUserId") long ownerUserId,
                           @Param("availableDelta") long availableDelta,
                           @Param("heldDelta") long heldDelta,
                           @Param("escrowedDelta") long escrowedDelta);
}
