package com.tongji.common.id;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.tongji.common.id.segment.SegmentIdGenerator;

@Component
public class DefaultIdService implements IdService {
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final SegmentIdGenerator segmentIdGenerator;

    public DefaultIdService(SnowflakeIdGenerator snowflakeIdGenerator) {
        this(snowflakeIdGenerator, null);
    }

    @Autowired
    public DefaultIdService(SnowflakeIdGenerator snowflakeIdGenerator, SegmentIdGenerator segmentIdGenerator) {
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.segmentIdGenerator = segmentIdGenerator;
    }

    @Override
    public long nextId(IdNamespace namespace) {
        if (namespace.getMode() == IdMode.SNOWFLAKE) {
            return snowflakeIdGenerator.nextId();
        }
        if (segmentIdGenerator == null) {
            throw new UnsupportedOperationException("Segment ID generation is not connected yet for namespace " + namespace);
        }
        return segmentIdGenerator.nextId(toSegmentBizTag(namespace));
    }

    private String toSegmentBizTag(IdNamespace namespace) {
        return switch (namespace) {
            case RECONCILIATION_TASK -> "reconciliation_task";
            case ADMIN_OPERATION -> "admin_operation";
            case AUDIT_LOG -> "audit_log";
            default -> throw new UnsupportedOperationException("Unsupported segment namespace " + namespace);
        };
    }
}
