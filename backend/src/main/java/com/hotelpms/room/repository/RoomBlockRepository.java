package com.hotelpms.room.repository;

import com.hotelpms.room.entity.RoomBlock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomBlockRepository extends JpaRepository<RoomBlock, Long> {

    /**
     * Lay tat ca block con hieu luc (active) cua mot phong theo thoi gian.
     * Dung de hien thi lich block cua phong cho nhan vien.
     */
    @Query("""
            SELECT b FROM RoomBlock b
            WHERE b.physicalRoom.id = :roomId
              AND b.releasedAt IS NULL
            ORDER BY b.startAt ASC
            """)
    List<RoomBlock> findActiveByRoomId(@Param("roomId") Long roomId);

    /**
     * Kiem tra phong co bi block trong khoang [from, to) khong.
     * Dung truoc khi tao booking de xac nhan phong kha dung.
     *
     * Overlap: block.startAt < to AND block.endAt > from
     */
    @Query("""
            SELECT COUNT(b) > 0
            FROM RoomBlock b
            WHERE b.physicalRoom.id = :roomId
              AND b.releasedAt IS NULL
              AND b.startAt < :to
              AND b.endAt > :from
            """)
    boolean isRoomBlocked(@Param("roomId") Long roomId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);

    /**
     * Lay cac block active overlap voi khoang thoi gian -- dung de bao loi ro rang.
     */
    @Query("""
            SELECT b FROM RoomBlock b
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
     * Lay lich su block (ca active lan da released) cua phong -- phan trang ngoai.
     */
    @Query("""
            SELECT b FROM RoomBlock b
            WHERE b.physicalRoom.id = :roomId
            ORDER BY b.startAt DESC
            """)
    List<RoomBlock> findAllByRoomIdOrderByStartAtDesc(@Param("roomId") Long roomId);
}