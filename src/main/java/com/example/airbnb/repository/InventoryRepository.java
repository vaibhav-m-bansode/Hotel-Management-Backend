package com.example.airbnb.repository;

import com.example.airbnb.entity.Hotel;
import com.example.airbnb.entity.Inventory;
import com.example.airbnb.entity.Room;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    void deleteInventoriesByDateAfterAndRoom(LocalDate dateAfter, Room room);

    void deleteByRoom(Room room);

    @Query("""
            SELECT DISTINCT i.hotel
            FROM Inventory i
            WHERE i.city = :city
              AND i.date BETWEEN :startDate AND :endDate
              AND i.closed = false
              AND (i.totalCount - i.bookedCount) >= :roomCount
            GROUP BY i.hotel, i.room
            HAVING COUNT(i.date) = :dateCount
            """)
    Page<Hotel> findHotelsWithAvailableInventory(
            @Param("city") String city,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("roomCount") Integer roomCount,
            @Param("dateCount") Long dateCount,
            Pageable pageable
    );

    @Query("""
            SELECT i
            FROM Inventory i
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND i.closed = false
              AND (i.totalCount - i.bookedCount - i.reservedCount) >= :roomsCount
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Inventory> findAndLockAvailableInventory(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("roomsCount") Integer roomsCount);

    @Query("""
            SELECT i
            FROM Inventory i
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND i.closed = false
              AND i.reservedCount >= :roomsCount
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Inventory> findAndLockReservedInventory(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("roomsCount") Integer roomsCount);

    @Query("""
            SELECT i
            FROM Inventory i
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND i.closed = false
              AND i.bookedCount >= :roomsCount
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Inventory> findAndLockBookedInventory(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("roomsCount") Integer roomsCount);

    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.reservedCount = i.reservedCount - :numberOfRooms,
                i.bookedCount = i.bookedCount + :numberOfRooms
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND i.reservedCount >= :numberOfRooms
              AND i.closed = false
            """)
    int confirmBooking(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("numberOfRooms") int numberOfRooms);

    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.reservedCount = i.reservedCount + :numberOfRooms
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND (i.totalCount - i.bookedCount - i.reservedCount) >= :numberOfRooms
              AND i.closed = false
            """)
    int initBooking(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("numberOfRooms") int numberOfRooms);

    List<Inventory> findByHotelAndDateBetween(
            Hotel hotel, LocalDate startDate, LocalDate endDate);

    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.bookedCount = i.bookedCount - :roomsCount
            WHERE i.room.id = :roomId
              AND i.date BETWEEN :startDate AND :endDate
              AND i.bookedCount >= :roomsCount
              AND i.closed = false
            """)
    int cancelBooking(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate checkInDate,
            @Param("endDate") LocalDate checkOutDate,
            @Param("roomsCount") Integer roomsCount);
}
