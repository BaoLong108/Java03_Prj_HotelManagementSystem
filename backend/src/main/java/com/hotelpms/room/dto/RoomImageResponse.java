package com.hotelpms.room.dto;

public record RoomImageResponse(long id, String url, boolean cover, int sortOrder, int width, int height) { }
