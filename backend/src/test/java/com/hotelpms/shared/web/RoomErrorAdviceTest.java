package com.hotelpms.shared.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hotelpms.room.controller.AdminRoomBlockController;
import com.hotelpms.room.service.RoomBlockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomErrorAdviceTest {
    @Test
    void missingRequiredRoomIdReturns400WithoutInvokingTheService() throws Exception {
        var blocks = mock(RoomBlockService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminRoomBlockController(blocks))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/admin/room-blocks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        verifyNoInteractions(blocks);
    }
}
