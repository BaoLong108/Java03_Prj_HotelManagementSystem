ALTER TABLE payment_attempts DROP CONSTRAINT ck_payment_attempts_provider;
ALTER TABLE payment_attempts ADD CONSTRAINT ck_payment_attempts_provider
    CHECK (provider IN ('DEMO','VNPAY','ZERO_PAYABLE'));
ALTER TABLE payment_attempts ADD CONSTRAINT ck_zero_settlement_amount
    CHECK (provider <> 'ZERO_PAYABLE' OR requested_amount = 0);
