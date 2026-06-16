package com.tongji.storage.text;

public class TextReadException extends TextStorageException {

    public TextReadException(String message) {
        super(message);
    }

    public TextReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
