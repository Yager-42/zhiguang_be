package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.config.PromotionProperties;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 竞价窗口管理：保证每个资源类型“当前 OPEN 窗口 + 下一个未来窗口”始终存在。
 * <p>窗口按 {@link PromotionProperties#getWindowMinutes()} 对齐到 epoch 整数倍边界滚动创建；
 * {@link #createWindow} 先查精确窗口再插入，幂等。</p>
 */
@Service
public class PromotionAuctionWindowService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final IdService idService;
    private final PromotionProperties properties;
    private final Clock clock;

    @Autowired
    public PromotionAuctionWindowService(PromotionAuctionWindowMapper windowMapper,
                                         IdService idService,
                                         PromotionProperties properties) {
        this(windowMapper, idService, properties, Clock.systemUTC());
    }

    public PromotionAuctionWindowService(PromotionAuctionWindowMapper windowMapper,
                                         IdService idService,
                                         PromotionProperties properties,
                                         Clock clock) {
        this.windowMapper = windowMapper;
        this.idService = idService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 保证指定资源类型存在当前 OPEN 窗口，并预建紧邻的下一个窗口；返回当前窗口。
     */
    @Transactional
    public PromotionAuctionWindow ensureOpenWindow(PromotionResourceType resourceType) {
        Instant now = clock.instant();
        PromotionAuctionWindow current = windowMapper.findOpenWindow(resourceType, now);
        if (current != null) {
            ensureNextWindow(resourceType, current.getWindowEndAt());
            return current;
        }
        PromotionAuctionWindow created = createWindow(resourceType, alignWindowStart(now));
        ensureNextWindow(resourceType, created.getWindowEndAt());
        return created;
    }

    private void ensureNextWindow(PromotionResourceType resourceType, Instant currentEndAt) {
        createWindow(resourceType, currentEndAt);
    }

    /**
     * 创建 [start, start+windowMinutes) 窗口；精确窗口已存在则直接返回（幂等）。
     */
    private PromotionAuctionWindow createWindow(PromotionResourceType resourceType, Instant startAt) {
        Instant endAt = startAt.plusSeconds(properties.getWindowMinutes() * 60L);
        PromotionAuctionWindow existing = windowMapper.findExactWindow(resourceType, startAt, endAt);
        if (existing != null) {
            return existing;
        }
        Instant now = clock.instant();
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .resourceType(resourceType)
                .windowStartAt(startAt)
                .windowEndAt(endAt)
                .slotCount(properties.slotCount(resourceType))
                .reservePrice(properties.reservePrice(resourceType))
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build();
        windowMapper.insert(window);
        return window;
    }

    private Instant alignWindowStart(Instant now) {
        long windowSeconds = properties.getWindowMinutes() * 60L;
        long aligned = (now.getEpochSecond() / windowSeconds) * windowSeconds;
        return Instant.ofEpochSecond(aligned);
    }
}
