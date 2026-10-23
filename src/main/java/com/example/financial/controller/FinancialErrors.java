package com.example.financial.controller;

import com.example.financial.service.OperationOrchestrator;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FinancialErrors {
    @ExceptionHandler(OperationOrchestrator.Unavailable.class)
    public ResponseEntity<Map<String, String>> unavailable(OperationOrchestrator.Unavailable failure) {
        return ResponseEntity.status(503).body(Map.of("error", failure.getMessage()));
    }
}
