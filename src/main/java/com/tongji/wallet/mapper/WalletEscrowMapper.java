package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletEscrow;
import com.tongji.wallet.model.WalletEscrowStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 通用托管单据持久化。
 * <p>
 * 状态迁移用 {@link #transitionStatus} 的条件更新（{@code WHERE status = fromStatus}）防并发双转账；
 * {@code transitionBusinessRef} 不落本表，只作为 wallet ledger 的幂等键。
 */
@Mapper
public interface WalletEscrowMapper {

    int insert(WalletEscrow escrow);

    WalletEscrow findById(@Param("id") long id);

    /** 行锁读取：迁移前锁住托管单，串行化并发 transition。 */
    WalletEscrow findByIdForUpdate(@Param("id") long id);

    WalletEscrow findByBusinessRef(@Param("businessRef") String businessRef);

    /**
     * 条件状态迁移：仅当当前状态为 {@code fromStatus} 时更新为 {@code toStatus}；返回受影响行数。
     */
    int transitionStatus(@Param("id") long id,
                         @Param("fromStatus") WalletEscrowStatus fromStatus,
                         @Param("toStatus") WalletEscrowStatus toStatus);
}
