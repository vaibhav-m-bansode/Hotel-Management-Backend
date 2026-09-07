package com.example.airbnb.service;

import com.example.airbnb.Exceptions.ResourceNotFoundException;
import com.example.airbnb.Exceptions.UnAuthorizedException;
import com.example.airbnb.Mapper.BookingMapper;
import com.example.airbnb.Mapper.GuestDtoMapper;
import com.example.airbnb.dto.BookingDto;
import com.example.airbnb.dto.BookingRequest;
import com.example.airbnb.dto.GuestDto;
import com.example.airbnb.entity.*;
import com.example.airbnb.entity.enums.BookingStatus;
import com.example.airbnb.repository.*;
import com.example.airbnb.strategy.PricingService;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {
    private final GuestRepository guestRepository;
    private final GuestDtoMapper guestDtoMapper;
    private final BookingMapper bookingMapper;
    private final BookingRepository bookingRepository;
    private final InventoryRepository inventoryRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final PricingService pricingService;
    private final CheckOutService checkOutService;

    @Value("${frontend_url}")
    private String frontendUrl;


    @Override
    @Transactional
    public BookingDto initializeBooking(BookingRequest bookingRequest) {
        log.info("BookingServiceImpl initializeBooking for {} , room:  {} , date {} - {}", bookingRequest.hotelId(), bookingRequest.roomId(), bookingRequest.checkInDate(), bookingRequest.checkOutDate());

        // Finding the hotel is existing
        Hotel hotel = hotelRepository.findById(bookingRequest.hotelId()).orElseThrow(() -> new ResourceNotFoundException("Hotel Not Found with id " + bookingRequest.hotelId()));
        //finding the room is existing
        Room room = roomRepository.findById(bookingRequest.roomId()).orElseThrow(() -> new ResourceNotFoundException("Room not found with id " + bookingRequest.roomId()));

        //finding the required inventory and locking it so other cannot access that inventory for particular time, prematurely
        List<Inventory> inventoryList = inventoryRepository.findAndLockAvailableInventory(bookingRequest.roomId(), bookingRequest.checkInDate(), bookingRequest.checkOutDate(), bookingRequest.roomsCount());
        long daysCount = ChronoUnit.DAYS.between(bookingRequest.checkInDate(), bookingRequest.checkOutDate()) + 1;

        if (inventoryList.size() < daysCount) {
            throw new IllegalStateException("Inventory Not Available");
        }

        //reserving the room/ update the booked count of inventories
        inventoryRepository.initBooking(room.getId(),bookingRequest.checkInDate(),bookingRequest.checkOutDate(),bookingRequest.roomsCount());

        // create the booking

        User user = getUser();

        //todo : calculate dynamic pricing
        BigDecimal priceForOneRoom  = inventoryList.stream()
                .map(pricingService::calculatePrice)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal totalPrice = priceForOneRoom.multiply(BigDecimal.valueOf(bookingRequest.roomsCount()));
        Booking booking = Booking.builder()
                .status(BookingStatus.RESERVED)
                .hotel(hotel).room(room)
                .checkInDate(bookingRequest.checkInDate())
                .checkOutDate(bookingRequest.checkOutDate())
                .user(user)
                .amount(totalPrice)
                .roomsCount(bookingRequest.roomsCount())
                .build();

        log.info("Creating a Booking for {}", booking.toString());

        booking = bookingRepository.save(booking);

        return bookingMapper.toDto(booking);
    }

    @Override
    @Transactional
    public BookingDto addGuests(Long bookingId, List<GuestDto> guestDtoList) {

        log.info("Adding guests for booking with {}", bookingId);

        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking Not Found with id " + bookingId));
        User user = getUser();

        if (!user.equals(booking.getUser())) {
            throw new UnAuthorizedException("Booking dose not belong to this user with id:" + user.getId());
        }
        if (hasBookingExpired(booking)) {
            throw new IllegalStateException("Booking Expired");
        }

        if (booking.getStatus() != BookingStatus.RESERVED) {
            throw new IllegalStateException("Booking Status Not under the RESERVED status");
        }

        for (GuestDto guestDto : guestDtoList) {
            Guest guest = guestDtoMapper.toEntity(guestDto);
            guest.setUser(getUser());
            guest = guestRepository.save(guest);
            booking.getGuests().add(guest);
        }
        log.info("Added guests for booking with {}", bookingId);

        booking.setStatus(BookingStatus.GUEST_ADDED);

        log.info("saving the booking for {}", bookingId);

        booking = bookingRepository.save(booking);

        return bookingMapper.toDto(booking);

    }

    @Override
    @Transactional
    public String initiatePayment(Long bookingId) {

        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking Not Found with id " + bookingId));
        User user = getUser();

        if (!user.equals(booking.getUser())) {
            throw new UnAuthorizedException("Booking dose not belong to this user with id:" + user.getId());
        }
        if (hasBookingExpired(booking)) {
            throw new IllegalStateException("Booking Expired");
        }

        String sessionUrl = checkOutService.getCheckOutSession(booking, frontendUrl + "/payments/success", frontendUrl + "/payments/failure");

        booking.setStatus(BookingStatus.PAYMENT_PENDING);
        booking = bookingRepository.save(booking);
        return sessionUrl;
    }

    @Override
    public void capturePayments(Event event) {

        if("checkout.session.complete".equals(event.getType())){
            Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
            if(session == null){return;}
            String sessionId = session.getId();
            Booking booking = bookingRepository.findByPaymentSessionId(sessionId).orElseThrow(
                    ()-> new ResourceNotFoundException(""));
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);

            inventoryRepository.findAndLockReservedInventory(booking.getRoom().getId(), booking.getCheckInDate(),
                    booking.getCheckOutDate(),booking.getRoomsCount());
            inventoryRepository.confirmBooking(booking.getRoom().getId(), booking.getCheckInDate(),
                    booking.getCheckOutDate(),booking.getRoomsCount());

            log.info("successfully captured and confirmed  payments for {}", booking.toString());
        }else {
            log.warn("Unhandled event type {}", event.getType());
        }
    }

    @Override
    public void cancelBooking(Long bookingId) {
        log.info("Cancelling booking for {}", bookingId);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking Not Found with id " + bookingId));
        User user = getUser();

        if (!user.equals(booking.getUser())) {
            throw new UnAuthorizedException("Booking dose not belong to this user with id:" + user.getId());
        }
        if(booking.getStatus() != BookingStatus.CONFIRMED){
            throw new IllegalStateException("Booking cannot be canceled");
        }

        inventoryRepository.findAndLockReservedInventory(booking.getRoom().getId(), booking.getCheckInDate(),
                booking.getCheckOutDate(),booking.getRoomsCount());
        inventoryRepository.cancelBooking(booking.getRoom().getId(), booking.getCheckInDate(),
                booking.getCheckOutDate(),booking.getRoomsCount());

        try {
            Session session = Session.retrieve(booking.getPaymentSessionId());
            RefundCreateParams refundCreateParams = RefundCreateParams.builder()
                    .setPaymentIntent(session.getPaymentIntent())
                    .build();
            Refund.create(refundCreateParams);
        } catch (StripeException e) {
            throw new RuntimeException(e);
        }
        return;
    }

    private boolean hasBookingExpired(Booking booking) {
        return booking.getCreatedAt().plusMinutes(10).isBefore(LocalDateTime.now());
    }

    private User getUser() {

        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
