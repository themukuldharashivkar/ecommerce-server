-- Paid orders that are cancelled (or paid after they expired) are refunded through Razorpay.
ALTER TABLE orders ADD COLUMN razorpay_refund_id varchar(255);
ALTER TABLE orders DROP CONSTRAINT orders_payment_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REFUNDED'));
