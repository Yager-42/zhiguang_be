package com.tongji.promotion.bprime.realtime;

import java.security.Principal;

public record PromotionAuctionWebSocketPrincipal(String name) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}
