package com.tongji.wallet.mapper;

import com.tongji.wallet.model.WalletLedgerEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 只追加钱包流水持久化。
 * <p>
 * 幂等键由 {@code (owner_user_id, business_ref)} 唯一约束保证；direct transfer 的 payer/payee 两条
 * 可共用同一 {@code business_ref}（owner 不同），故按 owner 精确查单条用 {@link #findByOwnerUserIdAndBusinessRef}。
 * 跨 owner 的"同一 business_ref 全局串行化"由 {@link #claimBusinessRef} 在 {@code wallet_business_ref} 表上抢占。
 */
@Mapper
public interface WalletLedgerMapper {

    int insert(WalletLedgerEntry entry);

    /** 同 business_ref 下所有流水（含 direct transfer 的双方）。 */
    List<WalletLedgerEntry> findByBusinessRef(@Param("businessRef") String businessRef);

    /** 单 owner + business_ref 的幂等事实查询，无则 null。 */
    WalletLedgerEntry findByOwnerUserIdAndBusinessRef(@Param("ownerUserId") long ownerUserId,
                                                      @Param("businessRef") String businessRef);

    List<WalletLedgerEntry> listByOwnerUserId(@Param("ownerUserId") long ownerUserId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /**
     * 全局 claim business_ref（写 {@code wallet_business_ref}，PK = business_ref）。
     * 成功返回 1；该 ref 已被别的事务抢占时抛 {@code DuplicateKeyException}（唯一键冲突），
     * 以此跨 owner 串行化同一 business_ref。
     */
    int claimBusinessRef(@Param("businessRef") String businessRef,
                         @Param("ownerUserId") long ownerUserId,
                         @Param("createdAt") Instant createdAt);
}
