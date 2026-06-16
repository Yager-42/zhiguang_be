package com.tongji.storage.text;

public class TextStorageException extends RuntimeException {

    public TextStorageException(String message) {
        super(message);
    }

    public TextStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
