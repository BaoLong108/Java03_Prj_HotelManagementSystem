package com.hotelpms.room.service;

import com.hotelpms.room.dto.RoomImageResponse;
import com.hotelpms.shared.api.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Images belong to a RoomType, matching the customer selection and pricing model. */
@Service
public class RoomImageService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final JdbcTemplate jdbc;

    public RoomImageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ValidatedImage(byte[] data, String contentType, int width, int height) { }
    public record ImageContent(byte[] data, String contentType) { }

    public List<ValidatedImage> validate(List<MultipartFile> files, int coverIndex) {
        if (files == null || files.isEmpty() || files.size() > 5 || coverIndex < 0 || coverIndex >= files.size()) {
            throw invalid("Chọn từ 1 đến 5 ảnh và một ảnh bìa hợp lệ");
        }
        List<ValidatedImage> result = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid("Mỗi ảnh phải từ 1 byte đến 5 MB");
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("Chỉ chấp nhận ảnh JPEG hoặc PNG hợp lệ");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!format.equals("jpeg") && !format.equals("png")) throw invalid("Chỉ chấp nhận ảnh JPEG hoặc PNG");
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 6000 || height > 6000 || (long) width * height > 16_000_000) {
                        throw invalid("Ảnh tối đa 6000 px mỗi chiều và 16 triệu điểm ảnh");
                    }
                    BufferedImage image = reader.read(0);
                    var output = new ByteArrayOutputStream();
                    // Re-encode actual pixels: discard filenames, metadata and appended payloads.
                    if (!ImageIO.write(image, format, output) || output.size() > MAX_BYTES) throw invalid("Ảnh sau xử lý vượt quá 5 MB");
                    result.add(new ValidatedImage(output.toByteArray(), "image/" + format, width, height));
                } finally { reader.dispose(); }
            } catch (IOException | IllegalArgumentException e) {
                throw invalid("Không đọc được ảnh. Vui lòng chọn JPEG hoặc PNG hợp lệ");
            }
        }
        return List.copyOf(result);
    }

    /** Caller holds the RoomType write lock; room + gallery commit or roll back together. */
    @Transactional
    public void replace(long typeId, List<ValidatedImage> images, int coverIndex, boolean replaceExisting) {
        if (!replaceExisting && jdbc.queryForObject("SELECT count(*) FROM room_type_images WHERE room_type_id=?", Long.class, typeId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_GALLERY_EXISTS", "Hạng phòng đã có ảnh. Cần xác nhận thay bộ ảnh dùng chung");
        }
        jdbc.update("DELETE FROM room_type_images WHERE room_type_id=?", typeId);
        for (int i = 0; i < images.size(); i++) {
            var image = images.get(i);
            jdbc.update("""
                INSERT INTO room_type_images(room_type_id,sort_order,is_cover,content_type,image_data,width,height)
                VALUES (?,?,?,?,?,?,?)
                """, typeId, i, i == coverIndex, image.contentType(), image.data(), image.width(), image.height());
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, List<RoomImageResponse>> metadata(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, List<RoomImageResponse>> result = new LinkedHashMap<>();
        jdbc.query("SELECT id,room_type_id,is_cover,sort_order,width,height FROM room_type_images WHERE room_type_id IN ("
                + placeholders + ") ORDER BY room_type_id,is_cover DESC,sort_order", rs -> {
            long typeId = rs.getLong("room_type_id"), id = rs.getLong("id");
            result.computeIfAbsent(typeId, ignored -> new ArrayList<>()).add(new RoomImageResponse(id,
                    "/api/v1/rooms/" + typeId + "/images/" + id, rs.getBoolean("is_cover"),
                    rs.getInt("sort_order"), rs.getInt("width"), rs.getInt("height")));
        }, ids.toArray());
        return result;
    }

    @Transactional(readOnly = true)
    public ImageContent content(long typeId, long imageId) {
        return jdbc.query("""
            SELECT i.image_data,i.content_type FROM room_type_images i JOIN room_types t ON t.id=i.room_type_id
            WHERE i.room_type_id=? AND i.id=? AND t.is_active=true
            """, (rs, n) -> new ImageContent(rs.getBytes(1), rs.getString(2)), typeId, imageId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_IMAGE_NOT_FOUND", "Không tìm thấy ảnh phòng"));
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROOM_IMAGES", message); }
}
