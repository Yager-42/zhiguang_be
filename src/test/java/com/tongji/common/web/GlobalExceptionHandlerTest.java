package com.tongji.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void missingResourceReturnsStableNotFoundResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException exception = new NoResourceFoundException(
                HttpMethod.POST, "/api/v1/promotions/campaigns/1/bids");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "NOT_FOUND")
                .containsEntry("message", "请求资源不存在");
    }
}
