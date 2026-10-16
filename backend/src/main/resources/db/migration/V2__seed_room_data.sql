-- Seed data cho room_types và physical_rooms để test API.
-- Chỉ insert nếu chưa có dữ liệu (idempotent).

INSERT INTO room_types (code, name, description, max_occupancy, base_price_per_night, is_active)
SELECT * FROM (VALUES
    ('STANDARD',  'Standard Room',  'Phòng tiêu chuẩn, 1 giường đôi, view sân vườn.',           2, 800000.00,   TRUE),
    ('DELUXE',    'Deluxe Room',    'Phòng deluxe, 1 giường king, ban công nhìn ra hồ bơi.',     2, 1200000.00,  TRUE),
    ('SUITE',     'Suite',          'Suite cao cấp, phòng khách riêng, bồn tắm jacuzzi.',        3, 2500000.00,  TRUE),
    ('FAMILY',    'Family Room',    'Phòng gia đình, 2 giường đôi, diện tích rộng 45m².',        4, 1800000.00,  TRUE),
    ('PENTHOUSE', 'Penthouse',      'Penthouse tầng thượng, tầm nhìn panorama toàn thành phố.',  4, 5000000.00,  TRUE)
) AS v(code, name, description, max_occupancy, base_price_per_night, is_active)
WHERE NOT EXISTS (SELECT 1 FROM room_types WHERE code = v.code);

-- Physical rooms cho STANDARD (101–105)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number, TRUE
FROM room_types rt
CROSS JOIN (VALUES
    ('101', 1::SMALLINT), ('102', 1::SMALLINT), ('103', 1::SMALLINT),
    ('104', 1::SMALLINT), ('105', 1::SMALLINT)
) AS v(room_number, floor_number)
WHERE rt.code = 'STANDARD'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- Physical rooms cho DELUXE (201–204)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number, TRUE
FROM room_types rt
CROSS JOIN (VALUES
    ('201', 2::SMALLINT), ('202', 2::SMALLINT), ('203', 2::SMALLINT), ('204', 2::SMALLINT)
) AS v(room_number, floor_number)
WHERE rt.code = 'DELUXE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- Physical rooms cho SUITE (301–302)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number, TRUE
FROM room_types rt
CROSS JOIN (VALUES
    ('301', 3::SMALLINT), ('302', 3::SMALLINT)
) AS v(room_number, floor_number)
WHERE rt.code = 'SUITE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- Physical rooms cho FAMILY (401–403)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number, TRUE
FROM room_types rt
CROSS JOIN (VALUES
    ('401', 4::SMALLINT), ('402', 4::SMALLINT), ('403', 4::SMALLINT)
) AS v(room_number, floor_number)
WHERE rt.code = 'FAMILY'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- Physical rooms cho PENTHOUSE (P01)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, 'P01', 10::SMALLINT, TRUE
FROM room_types rt
WHERE rt.code = 'PENTHOUSE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = 'P01');
