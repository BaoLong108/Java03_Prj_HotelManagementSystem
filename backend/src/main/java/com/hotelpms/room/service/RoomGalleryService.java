package com.hotelpms.room.service;

import com.hotelpms.room.dto.GalleryEditRequest;
import com.hotelpms.room.dto.RoomImageResponse;
import com.hotelpms.shared.api.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Atomic edits to the existing RoomType gallery, using its existing image table and validator. */
@Service
public class RoomGalleryService {
    private final JdbcTemplate jdbc;
    private final RoomImageService images;
    public RoomGalleryService(JdbcTemplate jdbc, RoomImageService images) { this.jdbc = jdbc; this.images = images; }

    @Transactional
    public List<RoomImageResponse> save(long typeId, GalleryEditRequest request, List<MultipartFile> files) {
        var uploads = files == null || files.isEmpty() ? List.<RoomImageService.ValidatedImage>of() : images.validate(files, 0);
        if (request.items() == null || request.items().size() > 5) throw invalid();
        boolean validCover = request.items().isEmpty() ? request.coverIndex() == -1
                : request.coverIndex() >= 0 && request.coverIndex() < request.items().size();
        if (!validCover) throw invalid();
        if (jdbc.queryForList("SELECT id FROM room_types WHERE id=? FOR UPDATE", Long.class, typeId).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Không tìm thấy hạng phòng.");
        var current = jdbc.queryForList("SELECT * FROM room_type_images WHERE room_type_id=? ORDER BY sort_order", typeId);
        var revision = current.stream().map(row -> row.get("id") + ":" + row.get("sort_order") + ":" + row.get("is_cover"))
                .collect(java.util.stream.Collectors.joining("|"));
        if (!revision.equals(request.expectedRevision())) throw new ApiException(HttpStatus.CONFLICT, "GALLERY_CHANGED",
                "Bộ ảnh đã được thay đổi ở phiên khác. Tải lại trước khi chỉnh sửa.");
        var ids = new HashSet<Long>(); var indices = new HashSet<Integer>();
        for (var item : request.items()) {
            if (item == null || (item.imageId() == null) == (item.uploadIndex() == null)) throw invalid();
            if (item.imageId() != null && (!ids.add(item.imageId()) || current.stream().noneMatch(row -> ((Number) row.get("id")).longValue() == item.imageId()))) throw invalid();
            if (item.uploadIndex() != null && (!indices.add(item.uploadIndex()) || item.uploadIndex() < 0 || item.uploadIndex() >= uploads.size())) throw invalid();
        }
        if (indices.size() != uploads.size()) throw invalid();
        // Bytea storage has no external orphan files. Delete + reinsert retained IDs avoids position/cover uniqueness collisions.
        jdbc.update("DELETE FROM room_type_images WHERE room_type_id=?", typeId);
        for (int position = 0; position < request.items().size(); position++) {
            var item = request.items().get(position);
            if (item.imageId() != null) {
                var row = current.stream().filter(value -> ((Number) value.get("id")).longValue() == item.imageId()).findFirst().orElseThrow();
                jdbc.update("""
                    INSERT INTO room_type_images(id,room_type_id,sort_order,is_cover,content_type,image_data,width,height,created_at,thumbnail_data,thumbnail_content_type)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, item.imageId(), typeId, position, position == request.coverIndex(), row.get("content_type"), row.get("image_data"),
                        row.get("width"), row.get("height"), row.get("created_at"), row.get("thumbnail_data"), row.get("thumbnail_content_type"));
            } else {
                var image = uploads.get(item.uploadIndex());
                jdbc.update("""
                    INSERT INTO room_type_images(room_type_id,sort_order,is_cover,content_type,image_data,width,height,thumbnail_data,thumbnail_content_type)
                    VALUES (?,?,?,?,?,?,?,?, 'image/jpeg')
                    """, typeId, position, position == request.coverIndex(), image.contentType(), image.data(), image.width(), image.height(), image.thumbnail());
            }
        }
        return images.metadata(List.of(typeId)).getOrDefault(typeId, List.of());
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GALLERY", "Bộ ảnh có tối đa 5 ảnh thuộc hạng phòng và cần đúng một ảnh bìa khi có ảnh."); }
}
