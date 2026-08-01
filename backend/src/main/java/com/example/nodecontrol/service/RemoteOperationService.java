package com.example.nodecontrol.service;

import com.example.nodecontrol.domain.ManagedNode;
import com.example.nodecontrol.domain.RemoteOperation;
import com.example.nodecontrol.domain.RemoteOperationRepository;
import com.example.nodecontrol.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RemoteOperationService {

    private final RemoteOperationRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    public RemoteOperationService(RemoteOperationRepository repository,
                                  ObjectMapper objectMapper,
                                  SecretCipher secretCipher) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    public <T> T execute(ManagedNode node,
                         String suppliedKey,
                         String operationType,
                         Object request,
                         Class<T> responseType,
                         Supplier<T> remoteCall) {
        String operationKey = normalizeKey(suppliedKey);
        String requestHash = hash(request);
        RemoteOperation operation = repository.findByOperationKey(operationKey).orElse(null);
        if (operation != null) {
            validate(operation, node, operationType, requestHash);
            if ("SUCCEEDED".equals(operation.getState())) {
                return readResponse(operation.getResponseCipher(), responseType);
            }
            if ("IN_PROGRESS".equals(operation.getState())) {
                throw new IllegalStateException("相同幂等请求正在执行中");
            }
            operation.retry();
        } else {
            operation = new RemoteOperation(operationKey, node.getId(), operationType, requestHash);
        }
        try {
            operation = repository.saveAndFlush(operation);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("相同幂等请求正在执行中", exception);
        }
        try {
            T response = remoteCall.get();
            operation.succeed(secretCipher.encrypt(writeResponse(response)));
            repository.save(operation);
            return response;
        } catch (RuntimeException exception) {
            operation.fail(exception.getMessage());
            repository.save(operation);
            throw exception;
        }
    }

    private void validate(RemoteOperation operation,
                          ManagedNode node,
                          String operationType,
                          String requestHash) {
        if (!operation.getNodeId().equals(node.getId())
                || !operation.getOperationType().equals(operationType)
                || !operation.getRequestHash().equals(requestHash)) {
            throw new IllegalStateException("幂等键已被其他请求使用");
        }
    }

    private String normalizeKey(String suppliedKey) {
        if (suppliedKey == null || suppliedKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String key = suppliedKey.trim();
        if (key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 不能超过 128 个字符");
        }
        return key;
    }

    private String hash(Object request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash operation request", exception);
        }
    }

    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not persist operation response", exception);
        }
    }

    private <T> T readResponse(String responseCipher, Class<T> responseType) {
        try {
            return objectMapper.readValue(secretCipher.decrypt(responseCipher), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read persisted operation response", exception);
        }
    }
}
