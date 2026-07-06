package com.tongji.common.singleflight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
public class SingleFlightResultCodec {

    private final ObjectMapper objectMapper;

    public SingleFlightResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> SingleFlightStoredResult serialize(T value, Long ownerToken, SingleFlightPolicy policy) {
        byte[] rawBytes = toJsonBytes(value);
        int threshold = policy == null ? Integer.MAX_VALUE : policy.compressionThresholdBytes();
        String codec = normalizeCodec(policy == null ? null : policy.compressionCodec());
        boolean shouldCompress = rawBytes.length >= threshold && !"none".equals(codec);
        byte[] storedBytes = shouldCompress ? gzip(rawBytes) : rawBytes;
        return new SingleFlightStoredResult(
                Base64.getEncoder().encodeToString(storedBytes),
                shouldCompress ? codec : "none",
                shouldCompress,
                rawBytes.length,
                storedBytes.length,
                sha256Hex(rawBytes),
                "application/json",
                System.currentTimeMillis(),
                ownerToken
        );
    }

    public <T> T deserialize(SingleFlightStoredResult storedResult, TypeReference<T> resultType) {
        if (storedResult == null || storedResult.payload() == null || storedResult.payload().isBlank()) {
            return null;
        }
        byte[] storedBytes = Base64.getDecoder().decode(storedResult.payload());
        byte[] rawBytes = storedResult.compressed() ? gunzip(storedBytes) : storedBytes;
        String checksum = sha256Hex(rawBytes);
        if (storedResult.checksum() != null && !storedResult.checksum().isBlank()
                && !storedResult.checksum().equals(checksum)) {
            throw new IllegalStateException("single-flight result checksum mismatch");
        }
        try {
            return objectMapper.readValue(rawBytes, resultType);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to decode single-flight result", exception);
        }
    }

    private <T> byte[] toJsonBytes(T value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode single-flight result", exception);
        }
    }

    private String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return "gzip";
        }
        String normalized = codec.trim().toLowerCase(Locale.ROOT);
        return "gzip".equals(normalized) ? normalized : "none";
    }

    private byte[] gzip(byte[] rawBytes) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
                gzipOutputStream.write(rawBytes);
            }
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to gzip single-flight result", exception);
        }
    }

    private byte[] gunzip(byte[] storedBytes) {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(storedBytes))) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            gzipInputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to gunzip single-flight result", exception);
        }
    }

    private String sha256Hex(byte[] rawBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
