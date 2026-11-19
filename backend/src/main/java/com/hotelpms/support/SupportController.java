package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class SupportController {
    private final SupportChat chat;
    private final SupportConcierge concierge;
    private final SupportSockets sockets;
    private final ConciergeProvider provider;
    private final HotelFaqs faqs;

    SupportController(SupportChat chat, SupportConcierge concierge, SupportSockets sockets, ConciergeProvider provider, HotelFaqs faqs) {
        this.chat = chat;
        this.concierge = concierge;
        this.sockets = sockets;
        this.provider = provider;
        this.faqs = faqs;
    }

    @GetMapping("/support/status")
    Map<String, Object> status() { return Map.of("aiConfigured", provider.available(), "staffOnline", sockets.onlineStaff()); }

    @PostMapping("/support/ai/message")
    SupportConcierge.Answer ai(Authentication auth, HttpServletRequest http, @Valid @RequestBody SupportConcierge.Request request) {
        return concierge.respond(Actor.from(auth), http.getRemoteAddr(), request);
    }

    @PostMapping("/support/conversations")
    Conversation open(Authentication auth) { return chat.open(Actor.from(auth)); }

    @PostMapping("/support/conversations/{id}/request-staff")
    Conversation handoff(@PathVariable long id, Authentication auth) { return chat.handoff(id, Actor.from(auth)); }

    @PostMapping("/staff/support/conversations/{id}/claim")
    Conversation claim(@PathVariable long id, Authentication auth) { return chat.claim(id, Actor.from(auth)); }

    @PostMapping("/staff/support/conversations/{id}/close")
    Conversation close(@PathVariable long id, Authentication auth) { return chat.close(id, Actor.from(auth)); }

    @GetMapping({"/support/conversations", "/staff/support/conversations"})
    List<Conversation> conversations(Authentication auth, HttpServletRequest http, @RequestParam(defaultValue = "9223372036854775807") long before) {
        return chat.list(Actor.from(auth), staff(http), before);
    }

    @GetMapping({"/support/conversations/{id}", "/staff/support/conversations/{id}"})
    Conversation detail(@PathVariable long id, Authentication auth, HttpServletRequest http) {
        return chat.get(id, Actor.from(auth), staff(http));
    }

    @GetMapping({"/support/conversations/{id}/messages", "/staff/support/conversations/{id}/messages"})
    List<Message> history(@PathVariable long id, @RequestParam(required = false) Long before, @RequestParam(required = false) Long after,
            Authentication auth, HttpServletRequest http) {
        return chat.messages(id, Actor.from(auth), staff(http), before, after);
    }

    @PostMapping({"/support/conversations/{id}/messages", "/staff/support/conversations/{id}/messages"})
    Message send(@PathVariable long id, @Valid @RequestBody Send body, Authentication auth, HttpServletRequest http) {
        return chat.send(id, Actor.from(auth), staff(http), body, false);
    }

    record ReadRequest(long through) {}

    @PostMapping({"/support/conversations/{id}/read", "/staff/support/conversations/{id}/read"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@PathVariable long id, @RequestBody ReadRequest body, Authentication auth, HttpServletRequest http) {
        chat.read(id, Actor.from(auth), staff(http), body.through());
    }

    @GetMapping("/admin/support/faqs")
    List<Faq> faqs(@RequestParam(defaultValue = "9223372036854775807") long before, @RequestParam(defaultValue = "") @Size(max = 120) String q) {
        return faqs.list(before, q.strip());
    }

    @PostMapping("/admin/support/faqs")
    @ResponseStatus(HttpStatus.CREATED)
    Faq createFaq(@Valid @RequestBody FaqInput input, Authentication auth) {
        return faqs.save(null, input, Actor.from(auth).id());
    }

    @PutMapping("/admin/support/faqs/{id}")
    Faq updateFaq(@PathVariable long id, @Valid @RequestBody FaqInput input, Authentication auth) {
        return faqs.save(id, input, Actor.from(auth).id());
    }

    private static boolean staff(HttpServletRequest http) { return http.getServletPath().startsWith("/api/v1/staff/"); }
}
