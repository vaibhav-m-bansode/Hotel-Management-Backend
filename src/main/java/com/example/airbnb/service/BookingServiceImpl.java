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
import java.time.LocalDate;
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

    @Value("${frontend.url}")
    private String frontendUrl;

    @Override
    @Transactional
    public BookingDto initializeBooking(BookingRequest bookingRequest) {
        log.info("Initializing booking for hotel {}, room {}, dates {} - {}",
                bookingRequest.hotelId(),
                bookingRequest.roomId(),
                bookingRequest.checkInDate(),
                bookingRequest.checkOutDate());

        validateBookingDates(bookingRequest.checkInDate(), bookingRequest.checkOutDate());

        Hotel hotel = hotelRepository.findById(bookingRequest.hotelId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hotel not found with id " + bookingRequest.hotelId()));

        Room room = roomRepository.findById(bookingRequest.roomId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Room not found with id " + bookingRequest.roomId()));

        if (!room.getHotel().getId().equals(hotel.getId())) {
            throw new IllegalArgumentException("Room does not belong to the requested hotel");
        }

        List<Inventory> inventoryList = inventoryRepository.findAndLockAvailableInventory(
                bookingRequest.roomId(),
                bookingRequest.checkInDate(),
                bookingRequest.checkOutDate(),
                bookingRequest.roomsCount());

        long daysCount = ChronoUnit.DAYS.between(
                bookingRequest.checkInDate(),
                bookingRequest.checkOutDate()) + 1;

        if (inventoryList.size() < daysCount) {
            throw new IllegalStateException("Inventory not available for the requested dates");
        }

        inventoryRepository.initBooking(
                room.getId(),
                bookingRequest.checkInDate(),
                bookingRequest.checkOutDate(),
                bookingRequest.roomsCount());

        User user = getUser();

        BigDecimal priceForOneRoom = inventoryList.stream()
                .map(pricingService::calculatePrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPrice = priceForOneRoom.multiply(
                BigDecimal.valueOf(bookingRequest.roomsCount()));

        Booking booking = Booking.builder()
                .status(BookingStatus.RESERVED)
                .hotel(hotel)
                .room(room)
                .checkInDate(bookingRequest.checkInDate())
                .checkOutDate(bookingRequest.checkOutDate())
                .user(user)
                .amount(totalPrice)
                .roomsCount(bookingRequest.roomsCount())
                .build();

        booking = bookingRepository.save(booking);

        log.info("Created booking {}", booking.getId());
        return bookingMapper.toDto(booking);
    }

    @Override
    @Transactional
    public BookingDto addGuests(Long bookingId, List<GuestDto> guestDtoList) {
        log.info("Adding guests for booking {}", bookingId);

        Booking booking = getBooking(bookingId);
        User user = getUser();

        verifyBookingOwnership(booking, user);

        if (hasBookingExpired(booking)) {
            booking.setStatus(BookingStatus.EXPIRED);
            throw new IllegalStateException("Booking expired");
        }

        if (booking.getStatus() != BookingStatus.RESERVED) {
            throw new IllegalStateException("Booking must be in RESERVED status");
        }

        for (GuestDto guestDto : guestDtoList) {
            Guest guest = guestDtoMapper.toEntity(guestDto);
            guest.setUser(user);
            guest = guestRepository.save(guest);
            booking.getGuests().add(guest);
        }

        booking.setStatus(BookingStatus.GUEST_ADDED);
        return bookingMapper.toDto(bookingRepository.save(booking));
    }

    @Override
    @Transactional
    public String initiatePayment(Long bookingId) {
        Booking booking = getBooking(bookingId);
        User user = getUser();

        verifyBookingOwnership(booking, user);

        if (hasBookingExpired(booking)) {
            booking.setStatus(BookingStatus.EXPIRED);
            throw new IllegalStateException("Booking expired");
        }

        if (booking.getStatus() != BookingStatus.GUEST_ADDED) {
            throw new IllegalStateException("Guests must be added before payment");
        }

        String sessionUrl = checkOutService.getCheckOutSession(
                booking,
                frontendUrl + "/payments/success",
                frontendUrl + "/payments/failure");

        booking.setStatus(BookingStatus.PAYMENT_PENDING);
        bookingRepository.save(booking);

        return sessionUrl;
    }

    @Override
    @Transactional
    public void capturePayments(Event event) {
        if (!"checkout.session.completed".equals(event.getType())) {
            log.warn("Unhandled Stripe event type {}", event.getType());
            return;
        }

        Session session = (Session) event.getDataObjectDeserializer()
                .getObject()
                .orElse(null);

        if (session == null) {
            log.warn("Stripe checkout session could not be deserialized");
            return;
        }

        Booking booking = bookingRepository.findByPaymentSessionId(session.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found for payment session " + session.getId()));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Payment webhook already processed for booking {}", booking.getId());
            return;
        }

        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            throw new IllegalStateException(
                    "Booking is not awaiting payment: " + booking.getStatus());
        }

        List<Inventory> inventory = inventoryRepository.findAndLockReservedInventory(
                booking.getRoom().getId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                booking.getRoomsCount());

        long expectedDays = ChronoUnit.DAYS.between(
                booking.getCheckInDate(), booking.getCheckOutDate()) + 1;

        if (inventory.size() < expectedDays) {
            throw new IllegalStateException("Reserved inventory not available for confirmation");
        }

        inventoryRepository.confirmBooking(
                booking.getRoom().getId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                booking.getRoomsCount());

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        log.info("Payment captured and booking {} confirmed", booking.getId());
    }

    @Override
    @Transactional
    public void cancelBooking(Long bookingId) {
        log.info("Cancelling booking {}", bookingId);

        Booking booking = getBooking(bookingId);
        User user = getUser();

        verifyBookingOwnership(booking, user);

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only confirmed bookings can be cancelled");
        }

        List<Inventory> inventory = inventoryRepository.findAndLockBookedInventory(
                booking.getRoom().getId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                booking.getRoomsCount());

        long expectedDays = ChronoUnit.DAYS.between(
                booking.getCheckInDate(), booking.getCheckOutDate()) + 1;

        if (inventory.size() < expectedDays) {
            throw new IllegalStateException("Booked inventory not available for cancellation");
        }

        try {
            Session session = Session.retrieve(booking.getPaymentSessionId());

            RefundCreateParams refundCreateParams = RefundCreateParams.builder()
                    .setPaymentIntent(session.getPaymentIntent())
                    .build();

            Refund.create(refundCreateParams);

            inventoryRepository.cancelBooking(
                    booking.getRoom().getId(),
                    booking.getCheckInDate(),
                    booking.getCheckOutDate(),
                    booking.getRoomsCount());

            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);
        } catch (StripeException e) {
            throw new IllegalStateException("Unable to process booking refund", e);
        }
    }

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with id " + bookingId));
    }

    private void verifyBookingOwnership(Booking booking, User user) {
        if (!user.equals(booking.getUser())) {
            throw new UnAuthorizedException(
                    "Booking does not belong to user with id: " + user.getId());
        }
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }

    private boolean hasBookingExpired(Booking booking) {
        return booking.getCreatedAt() != null
                && booking.getCreatedAt().plusMinutes(10).isBefore(LocalDateTime.now());
    }

    private void validateBookingDates(LocalDate checkInDate, LocalDate checkOutDate) {
        if (checkInDate == null || checkOutDate == null
                || checkOutDate.isBefore(checkInDate)) {
            throw new IllegalArgumentException("Invalid booking dates");
        }
    }
}
