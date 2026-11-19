CREATE TABLE support_conversations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES app_users(id),
    assigned_staff_id BIGINT REFERENCES app_users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'AI_ACTIVE',
    customer_read_id BIGINT NOT NULL DEFAULT 0,
    staff_read_id BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    CONSTRAINT ck_support_conversation_state CHECK (
        (status IN ('AI_ACTIVE', 'WAITING_STAFF') AND assigned_staff_id IS NULL AND closed_at IS NULL)
        OR (status = 'STAFF_ACTIVE' AND assigned_staff_id IS NOT NULL AND accepted_at IS NOT NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND assigned_staff_id IS NOT NULL AND accepted_at IS NOT NULL AND closed_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_support_open_customer ON support_conversations(customer_id) WHERE status <> 'CLOSED';
CREATE INDEX ix_support_customer_history ON support_conversations(customer_id, updated_at DESC, id DESC);
CREATE INDEX ix_support_staff_inbox ON support_conversations(status, assigned_staff_id, updated_at DESC);

CREATE TABLE support_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES support_conversations(id) ON DELETE CASCADE,
    sender VARCHAR(12) NOT NULL CHECK (sender IN ('CUSTOMER', 'AI', 'STAFF')),
    sender_id BIGINT REFERENCES app_users(id),
    client_id UUID,
    content VARCHAR(2400) NOT NULL CHECK (btrim(content) <> ''),
    details JSONB,
    reply_to BIGINT UNIQUE REFERENCES support_messages(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_sender CHECK (
        (sender = 'AI' AND sender_id IS NULL AND client_id IS NULL)
        OR (sender IN ('CUSTOMER', 'STAFF') AND sender_id IS NOT NULL AND client_id IS NOT NULL)
    ),
    UNIQUE (conversation_id, sender, sender_id, client_id)
);
CREATE INDEX ix_support_message_history ON support_messages(conversation_id, id DESC);

CREATE TABLE hotel_faqs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category VARCHAR(60) NOT NULL,
    question VARCHAR(240) NOT NULL,
    answer VARCHAR(1600) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by BIGINT REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_hotel_faq_active ON hotel_faqs(active, id);
INSERT INTO hotel_faqs(category,question,answer) VALUES
    ('Nhận / trả phòng', 'Giờ nhận phòng và trả phòng là mấy giờ?', 'Giờ nhận phòng là 12:00. Giờ trả phòng là 10:00 theo giờ Việt Nam.'),
    ('Đặt phòng', 'Tôi cần làm gì để đặt phòng?', 'Chọn ngày nghỉ và hạng phòng, đăng nhập, xem lại báo giá rồi tạo giữ phòng. Hệ thống giữ phòng 15 phút để bạn hoàn tất thanh toán.');

CREATE TABLE support_rate_windows (
    scope VARCHAR(100) NOT NULL,
    window_at TIMESTAMPTZ NOT NULL,
    requests INT NOT NULL,
    PRIMARY KEY(scope, window_at)
);
