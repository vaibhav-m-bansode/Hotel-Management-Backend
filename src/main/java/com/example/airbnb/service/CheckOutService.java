package com.example.airbnb.service;

import com.example.airbnb.entity.Booking;

public interface CheckOutService {
    String getCheckOutSession(Booking booking, String successUrl , String failureUrl);
}
