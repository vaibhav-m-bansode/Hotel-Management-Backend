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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        for (Inventory inventory : inventoryList) {
            inventory.setReservedCount(inventory.getBookedCount() + bookingRequest.roomsCount());
        }

        inventoryRepository.saveAll(inventoryList);

        // create the booking
        //TODO : remove Dummy user
        User user = getUser();

        //todo : calculate dynamic pricing
        BigDecimal price = pricingService.calculatePrice( inventoryList.get(1));

        Booking booking = Booking.builder()
                .status(BookingStatus.RESERVED)
                .hotel(hotel).room(room)
                .checkInDate(bookingRequest.checkInDate())
                .checkOutDate(bookingRequest.checkOutDate())
                .user(user)
                .amount(price)
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

    private boolean hasBookingExpired(Booking booking) {
        return booking.getCreatedAt().plusMinutes(10).isBefore(LocalDateTime.now());
    }

    private User getUser() {

        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
