package com.tongji.common.id;

public interface IdService {

    long nextId(IdNamespace namespace);
}
