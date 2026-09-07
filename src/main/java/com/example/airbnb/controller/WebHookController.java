package com.example.airbnb.controller;

import com.example.airbnb.service.BookingService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebHookController {

    private final BookingService bookingService;

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;

    @PostMapping("/payment")
    public ResponseEntity<Void> capturePayment(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader) {
        try {
            Event event = Webhook.constructEvent(payload, signatureHeader, endpointSecret);
            bookingService.capturePayments(event);
            return ResponseEntity.noContent().build();
        } catch (SignatureVerificationException e) {
            throw new IllegalArgumentException("Invalid Stripe webhook signature", e);
        }
    }
}
