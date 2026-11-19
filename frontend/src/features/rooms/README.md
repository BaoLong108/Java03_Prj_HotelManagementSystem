Room catalog and admin room management. Owner: Lê Phước Sang. Task M2-07; guests select a room type, not a physical room.

StaySearchForm validates dates/party and writes shareable URL criteria. RoomsPage uses the existing BookingPage with real availability, quotes and RoomType cover images. RoomDetailPage loads roomApi metadata and reuses ImageGallery for a main image, horizontal thumbnails and a modal viewer.

AdminRoomsPage creates physical rooms with a JSON request for the room type, room number, floor and active status. Admins manage the shared gallery in Loại phòng → Quản lý ảnh. A nonempty gallery has 1–5 JPEG/PNG files and exactly one cover. Gallery edits use a multipart request with the expected gallery revision.

Booking allocates a single active physical room for the entire stay. Digits-only room numbers are sorted numerically first. Alphanumeric room numbers and numeric ties are sorted by case-insensitive lexical order using the PostgreSQL C collation, followed by exact spelling and ID.

Room images are persisted by `V4__room_images.sql`. Each image can be at most 5 MiB, 6000 px per side and 16 megapixels. The availability and RoomType responses provide `coverImageUrl`. RoomType details also provide ordered `images` metadata.
