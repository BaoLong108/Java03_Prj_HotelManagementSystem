package com.hotelpms.room.repository;

import com.hotelpms.room.entity.RoomBlock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomBlockRepository extends JpaRepository<RoomBlock, Long> {

    /** Block active cua mot phong, sap xep theo thoi gian bat dau. */
    @Query("""
            SELECT b FROM RoomBlock b
            JOIN FETCH b.physicalRoom
            WHERE b.physicalRoom.id = :roomId
              AND b.releasedAt IS NULL
            ORDER BY b.startAt ASC
            """)
    List<RoomBlock> findActiveByRoomId(@Param("roomId") Long roomId);

    /**
     * Lich su block (ca active va released) cua phong.
     * JOIN FETCH tranh lazy load khi map sang DTO.
     */
    @Query("""
            SELECT b FROM RoomBlock b
            JOIN FETCH b.physicalRoom
            WHERE b.physicalRoom.id = :roomId
            ORDER BY b.startAt DESC
            """)
    List<RoomBlock> findAllByRoomIdOrderByStartAtDesc(@Param("roomId") Long roomId);

    /**
     * Block active overlap voi khoang thoi gian [from, to).
     * Overlap: block.startAt < to AND block.endAt > from.
     * Dung de bao loi ro rang va kiem tra kha dung truoc khi tao booking.
     */
    @Query("""
            SELECT b FROM RoomBlock b
            JOIN FETCH b.physicalRoom
            WHERE b.physicalRoom.id = :roomId
              AND b.releasedAt IS NULL
              AND b.startAt < :to
              AND b.endAt > :from
            ORDER BY b.startAt ASC
            """)
    List<RoomBlock> findOverlapping(@Param("roomId") Long roomId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    /**
     * Dem so block active overlap -- dung de kiem tra nhanh khong load entity.
     * Thay the cho COUNT > 0 boolean (JPQL khong ho tro tra boolean tu COUNT).
     */
    @Query("""
            SELECT COUNT(b)
            FROM RoomBlock b
            WHERE b.physicalRoom.id = :roomId
              AND b.releasedAt IS NULL
              AND b.startAt < :to
              AND b.endAt > :from
            """)
    long countOverlapping(@Param("roomId") Long roomId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);

    /** Lay block theo id kem eager load physicalRoom -- dung cho API get detail / release. */
    @Query("""
            SELECT b FROM RoomBlock b
            JOIN FETCH b.physicalRoom pr
            JOIN FETCH pr.roomType
            WHERE b.id = :id
            """)
    Optional<RoomBlock> findByIdWithRoom(@Param("id") Long id);
}