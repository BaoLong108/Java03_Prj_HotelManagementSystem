Room catalog and admin room management. Owner: Lê Phước Sang. Task M2-07; guests select a room type, not a physical room.

StaySearchForm validates dates/party and writes shareable URL criteria. RoomsPage uses the existing BookingPage with real availability, quotes and RoomType cover images. RoomDetailPage loads roomApi metadata and reuses ImageGallery for a main image, horizontal thumbnails and a modal viewer.

AdminRoomsPage loads RoomTypes/prices from the database and creates physical rooms. A RoomType without images requires 1–5 JPEG/PNG files and one cover. Existing images are reused by default; selecting replacement files also requires explicit confirmation. Price and gallery are shared by the RoomType. Multipart requests use FormData through the existing API client; JSON/auth contracts remain available.

Room images are persisted by `V4__room_images.sql`. Each image can be at most 5 MiB, 6000 px per side and 16 megapixels. The availability and RoomType responses provide `coverImageUrl`. RoomType details also provide ordered `images` metadata.
