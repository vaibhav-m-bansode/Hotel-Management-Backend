package com.example.airbnb.service;

import com.example.airbnb.entity.Booking;
import com.example.airbnb.entity.User;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Slf4j
@Service
public class CheckOutServiceImpl implements  CheckOutService {
    @Transactional
    @Override
    public String getCheckOutSession(Booking booking, String successUrl, String failureUrl) {
        log.info("Checking out session");
        log.info("Creating Checkout Session for booking {}", booking);
        User user = (User) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();

        try {
            Customer customer = Customer.create(CustomerCreateParams.builder()
                            .setName(user.getUsername())
                            .setEmail(user.getUsername())
                            .build());
            SessionCreateParams sessionParams = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                    .setCustomer(customer.getId())
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(failureUrl)
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency("inr")
                                                    .setUnitAmount(booking.getAmount().multiply(BigDecimal.valueOf(100)).longValue())
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName(booking.getHotel().getName() + ": " + booking.getRoom().getType())
                                                                    .setDescription("Booking id: "+ booking.getId())
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();
            Session session = Session.create(sessionParams);

            booking.setPaymentSessionId(session.getId());
            log.info("Session created");
            return session.getUrl();
        } catch (StripeException e) {
            throw new RuntimeException(e);
        }

    }
}
