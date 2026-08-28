package org.example.vo;

import org.slf4j.MDC;

import java.util.UUID;

public class ApiV1Response<T> {
    private int code;
    private String msg;
    private T data;
    private String traceId;
    private long timestamp;

    public ApiV1Response() {
    }

    public ApiV1Response(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        String currentTraceId = MDC.get("traceId");
        this.traceId = currentTraceId == null || currentTraceId.isEmpty()
                ? UUID.randomUUID().toString()
                : currentTraceId;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiV1Response<T> ok(T data) {
        return new ApiV1Response<>(0, "ok", data);
    }

    public static <T> ApiV1Response<T> error(int code, String msg) {
        return new ApiV1Response<>(code, msg, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
