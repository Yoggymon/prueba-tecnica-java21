package com.example.financial.controller;

import com.example.financial.model.Operation;
import com.example.financial.model.TransferRequest;
import com.example.financial.service.OperationOrchestrator;
import com.example.financial.service.OperationRegistration;

import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class TransferController {
    private static final Logger LOG = LoggerFactory.getLogger(TransferController.class);
    private final OperationOrchestrator orchestrator;
    private final ObjectReader json;

    public TransferController(OperationOrchestrator orchestrator, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        // Match withdrawal decimal parsing without changing its established input contract.
        this.json = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping(value = "/transfers", consumes = "application/json")
    public ResponseEntity<Operation.Result> transfer(@RequestBody String body) throws JsonProcessingException {
        JsonNode input = json.readTree(body);
        if (input == null || !input.isObject() || !input.path("key").isTextual()
                || !input.path("sourceId").isIntegralNumber() || !input.path("sourceId").canConvertToLong()
                || !input.path("destinationId").isIntegralNumber() || !input.path("destinationId").canConvertToLong()
                || !input.path("amount").isNumber()) {
            throw new IllegalArgumentException("Invalid transfer request");
        }
        var request = new TransferRequest(input.path("key").textValue(), input.path("sourceId").longValue(),
                input.path("destinationId").longValue(), input.path("amount").decimalValue());
        var result = orchestrator.execute(request);
        return ResponseEntity.status(result.status().equals("SUCCESS") ? 200
                : result.status().equals("REJECTED") ? 422 : 503).body(result);
    }

    @ExceptionHandler({IllegalArgumentException.class, JsonProcessingException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidRequest() {
        LOG.warn("Transfer request rejected: INVALID_REQUEST");
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }

    @ExceptionHandler(OperationRegistration.IdempotencyConflict.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("error", "IDEMPOTENCY_CONFLICT"));
    }
}
