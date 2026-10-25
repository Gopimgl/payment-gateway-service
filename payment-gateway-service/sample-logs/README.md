# Sample logs

The application writes its log to `logs/payment-service.log` (project root) every time it runs.
After you run the Postman collection, copy that file here as `payment-service-sample.log`:

    # macOS / Linux
    cp logs/payment-service.log sample-logs/payment-service-sample.log
    # Windows (cmd)
    copy logs\payment-service.log sample-logs\payment-service-sample.log

Every line carries a correlation ID (`cid=...`), so one request can be followed end to end.
To read a single payment's story: `grep "PAY-XXXXXXXXXXXX" sample-logs/payment-service-sample.log`
(Windows: `findstr PAY-XXXXXXXXXXXX sample-logs\payment-service-sample.log`).
