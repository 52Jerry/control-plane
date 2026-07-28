package com.example.nodecontrol.client;

public class RemoteNodeException extends RuntimeException {

    private final int statusCode;

    public RemoteNodeException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public RemoteNodeException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

