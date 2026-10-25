package com.matrix.payments.web;

import com.matrix.payments.dto.CallbackResponse;
import com.matrix.payments.service.CallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/callback")
@Tag(name = "Callbacks")
public class CallbackController {

    private final CallbackService service;

    public CallbackController(CallbackService service) {
        this.service = service;
    }

    @Operation(summary = "Gateway webhook",
            description = "Body is verified against the X-Signature header (hex HMAC-SHA256 of the raw body). "
                    + "Duplicate event IDs and conflicting final states are acknowledged but never re-applied.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallbackResponse callback(
            @RequestBody String rawBody,
            @Parameter(description = "Hex HMAC-SHA256 of the raw request body")
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        return service.handle(rawBody, signature);
    }
}
