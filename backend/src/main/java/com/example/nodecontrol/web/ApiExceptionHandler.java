package com.example.nodecontrol.web;

import com.example.nodecontrol.client.RemoteNodeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RemoteNodeException.class)
    public ResponseEntity<ErrorResponse> remoteNode(RemoteNodeException exception) {
        int remoteStatus = exception.getStatusCode();
        HttpStatus status = remoteStatus >= 400 && remoteStatus < 500
                ? HttpStatus.resolve(remoteStatus)
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status == null ? HttpStatus.BAD_GATEWAY : status)
                .body(new ErrorResponse(localizeKnownMessage(
                                exception.getMessage(), "节点请求失败（HTTP " + remoteStatus + "）"),
                        remoteStatus, Instant.now(), null));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(localizeKnownMessage(exception.getMessage(), "请求的数据不存在"),
                        404, Instant.now(), null));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> badRequest(RuntimeException exception) {
        HttpStatus status = exception instanceof DataIntegrityViolationException || exception instanceof IllegalStateException
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        String fallback = exception instanceof DataIntegrityViolationException
                ? "数据保存冲突，请检查是否存在重复记录"
                : "请求处理失败";
        return ResponseEntity.status(status)
                .body(new ErrorResponse(localizeKnownMessage(exception.getMessage(), fallback),
                        status.value(), Instant.now(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), validationMessage(error));
        }
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("请求参数不正确", 400, Instant.now(), fields));
    }

    private String validationMessage(FieldError error) {
        String defaultMessage = error.getDefaultMessage();
        if (containsChinese(defaultMessage)) {
            return defaultMessage;
        }
        String label = switch (error.getField()) {
            case "username" -> "账号";
            case "password" -> "密码";
            case "name" -> "名称";
            case "baseUrl" -> "API 地址";
            case "token", "apiToken" -> "访问令牌";
            case "nodeId" -> "节点标识";
            case "maxUsers" -> "最大用户数";
            case "userId" -> "用户 ID";
            case "protocols" -> "协议列表";
            case "input" -> "节点信息";
            case "userPrefix" -> "节点用户前缀";
            case "proxy.server" -> "代理服务器";
            case "proxy.port" -> "代理端口";
            case "proxy.username", "socksUsername" -> "SOCKS 用户名";
            case "proxy.password", "socksPassword" -> "SOCKS 密码";
            default -> error.getField();
        };
        return switch (error.getCode() == null ? "" : error.getCode()) {
            case "NotBlank" -> label + "不能为空";
            case "NotEmpty" -> label + "至少需要填写或选择一项";
            case "Size" -> label + "长度或数量不符合要求";
            case "Pattern" -> label + "格式不正确";
            case "Min", "Max" -> label + "数值超出允许范围";
            default -> label + "参数不正确";
        };
    }

    private String localizeKnownMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        if (message.contains("Could not decrypt secret")
                || message.contains("CONTROL_PLANE_ENCRYPTION_KEY")
                || message.contains("敏感数据解密失败")) {
            return "敏感数据解密失败，请检查本地与服务器的加密密钥是否一致";
        }
        return message
                .replace("Node Manager", "节点管理器")
                .replace("Unauthorized", "未授权")
                .replace("Forbidden", "无权执行此操作")
                .replace("Internal Server Error", "服务器内部错误");
    }

    private boolean containsChinese(String value) {
        return value != null && value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    public record ErrorResponse(
            String message,
            int status,
            Instant timestamp,
            Map<String, String> fields
    ) {
    }
}

