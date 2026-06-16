package com.tongji.relation.manager;

public record RelationWriteResult(boolean success, boolean stateChanged, boolean following) {

    public static RelationWriteResult changed(boolean following) {
        return new RelationWriteResult(true, true, following);
    }

    public static RelationWriteResult unchanged(boolean following) {
        return new RelationWriteResult(true, false, following);
    }

    public static RelationWriteResult failed() {
        return new RelationWriteResult(false, false, false);
    }
}
