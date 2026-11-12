CREATE TABLE vnpay_checkouts (
    payment_attempt_id BIGINT PRIMARY KEY REFERENCES payment_attempts(id) ON DELETE CASCADE,
    reference VARCHAR(100) NOT NULL UNIQUE,
    checkout_url TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_code VARCHAR(2),
    transaction_status VARCHAR(2),
    provider_transaction_id VARCHAR(100),
    received_at TIMESTAMPTZ,
    CONSTRAINT ck_vnpay_outcome CHECK (outcome IN ('PENDING','SUCCEEDED','FAILED','REVIEW_REQUIRED'))
);
CREATE TABLE booking_confirmation_mail (
    booking_id BIGINT PRIMARY KEY REFERENCES bookings(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ
);

CREATE FUNCTION enqueue_booking_confirmation_mail() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'CONFIRMED' AND OLD.status = 'PENDING_PAYMENT' THEN
        INSERT INTO booking_confirmation_mail(booking_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER booking_confirmation_mail_after_confirmation
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW EXECUTE FUNCTION enqueue_booking_confirmation_mail();
