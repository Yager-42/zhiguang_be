package com.tongji.storage.text;

public class TextWriteException extends TextStorageException {

    public TextWriteException(String message) {
        super(message);
    }

    public TextWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
