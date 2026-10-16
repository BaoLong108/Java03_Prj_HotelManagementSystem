-- =============================================================================
-- V2: Seed data dev/test
-- Idempotent: chi insert neu chua co du lieu.
-- =============================================================================

-- Tai khoan admin va nhan vien de test API co auth
INSERT INTO app_users (email, password_hash, full_name, is_active)
SELECT v.email, v.password_hash, v.full_name, TRUE
FROM (VALUES
    ('admin@hotel.dev',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVGGex94HW', 'Admin System'),
    ('staff@hotel.dev',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVGGex94HW', 'Nhan Vien A')
) AS v(email, password_hash, full_name)
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = v.email);
-- password cho ca 2 account: admin123

-- Gan role ADMIN cho admin@hotel.dev
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u, roles r
WHERE u.email = 'admin@hotel.dev' AND r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- Gan role STAFF cho staff@hotel.dev
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u, roles r
WHERE u.email = 'staff@hotel.dev' AND r.code = 'STAFF'
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- =============================================================================
-- Room Types
-- =============================================================================
INSERT INTO room_types (code, name, description, max_occupancy, base_price_per_night, is_active)
SELECT v.code, v.name, v.description, v.max_occupancy, v.base_price_per_night, TRUE
FROM (VALUES
    ('STANDARD',  'Standard Room',  'Phong tieu chuan, 1 giuong doi, view san vuon.',            2::SMALLINT, 800000.00::NUMERIC),
    ('DELUXE',    'Deluxe Room',    'Phong deluxe, 1 giuong king, ban cong nhin ra ho boi.',      2::SMALLINT, 1200000.00::NUMERIC),
    ('SUITE',     'Suite',          'Suite cao cap, phong khach rieng, bon tam jacuzzi.',          3::SMALLINT, 2500000.00::NUMERIC),
    ('FAMILY',    'Family Room',    'Phong gia dinh, 2 giuong doi, dien tich rong 45m2.',         4::SMALLINT, 1800000.00::NUMERIC),
    ('PENTHOUSE', 'Penthouse',      'Penthouse tang thuong, tam nhin panorama toan thanh pho.',    4::SMALLINT, 5000000.00::NUMERIC)
) AS v(code, name, description, max_occupancy, base_price_per_night)
WHERE NOT EXISTS (SELECT 1 FROM room_types WHERE code = v.code);

-- =============================================================================
-- Physical Rooms
-- =============================================================================

-- STANDARD: 101-105, tang 1 (101 inactive de test case an phong)
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number::SMALLINT, v.is_active
FROM room_types rt
CROSS JOIN (VALUES
    ('101', 1, TRUE),
    ('102', 1, TRUE),
    ('103', 1, TRUE),
    ('104', 1, TRUE),
    ('105', 1, FALSE)
) AS v(room_number, floor_number, is_active)
WHERE rt.code = 'STANDARD'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- DELUXE: 201-204, tang 2
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number::SMALLINT, TRUE
FROM room_types rt
CROSS JOIN (VALUES ('201',2),('202',2),('203',2),('204',2)) AS v(room_number, floor_number)
WHERE rt.code = 'DELUXE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- SUITE: 301-302, tang 3
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number::SMALLINT, TRUE
FROM room_types rt
CROSS JOIN (VALUES ('301',3),('302',3)) AS v(room_number, floor_number)
WHERE rt.code = 'SUITE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- FAMILY: 401-403, tang 4
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, v.room_number, v.floor_number::SMALLINT, TRUE
FROM room_types rt
CROSS JOIN (VALUES ('401',4),('402',4),('403',4)) AS v(room_number, floor_number)
WHERE rt.code = 'FAMILY'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = v.room_number);

-- PENTHOUSE: P01, tang 10
INSERT INTO physical_rooms (room_type_id, room_number, floor_number, is_active)
SELECT rt.id, 'P01', 10::SMALLINT, TRUE
FROM room_types rt
WHERE rt.code = 'PENTHOUSE'
  AND NOT EXISTS (SELECT 1 FROM physical_rooms WHERE room_number = 'P01');

-- =============================================================================
-- Room Blocks mau (block phong 201 va 301 de test API)
-- =============================================================================
INSERT INTO room_blocks (physical_room_id, start_at, end_at, reason, created_by_user_id, created_at)
SELECT pr.id,
       NOW() + INTERVAL '1 day',
       NOW() + INTERVAL '3 days',
       'Bao tri dinh ky',
       u.id,
       NOW()
FROM physical_rooms pr, app_users u
WHERE pr.room_number = '201' AND u.email = 'staff@hotel.dev'
  AND NOT EXISTS (
    SELECT 1 FROM room_blocks rb
    WHERE rb.physical_room_id = pr.id AND rb.released_at IS NULL
  );

INSERT INTO room_blocks (physical_room_id, start_at, end_at, reason, created_by_user_id, created_at)
SELECT pr.id,
       NOW() + INTERVAL '2 days',
       NOW() + INTERVAL '5 days',
       'Giu cho su kien cong ty',
       u.id,
       NOW()
FROM physical_rooms pr, app_users u
WHERE pr.room_number = '301' AND u.email = 'admin@hotel.dev'
  AND NOT EXISTS (
    SELECT 1 FROM room_blocks rb
    WHERE rb.physical_room_id = pr.id AND rb.released_at IS NULL
  );