# Payment Ledger Service

A production-grade, highly concurrent double-entry bookkeeping engine and payment ledger REST API built in **Java 21** with **Spring Boot 3**, **PostgreSQL**, **Flyway**, and **Testcontainers**.

---

## 🏛️ Architectural Overview

Financial accounting engines require strict mathematical and transactional invariants: money cannot appear or disappear, and historical records can never be rewritten or deleted. The `payment-ledger-service` satisfies these guarantees through four core pillars:

```
                            ┌────────────────────────┐
                            │  POST /api/v1/transfers│
                            └───────────┬────────────┘
                                        │
                         [Idempotency Header / Key Check]
                                        │
                          Deterministic UUID Sorting
                         (Deadlock Prevention Strategy)
                                        │
                          Optimistic Locking (@Version)
                            with Exponential Backoff
                                        │
           ┌────────────────────────────┴───────────────────────────┐
           ▼                                                        ▼
┌─────────────────────────────┐                         ┌─────────────────────────────┐
│  Source Account (DEBIT)     │                         │ Destination Account (CREDIT)│
│  Balance: Balance - Amount  │                         │ Balance: Balance + Amount   │
│  Version: Version + 1       │                         │ Version: Version + 1        │
└──────────────┬──────────────┘                         └──────────────┬──────────────┘
               │                                                       │
               ▼                                                       ▼
┌─────────────────────────────┐                         ┌─────────────────────────────┐
│  Ledger Entry 1 (DEBIT)     │                         │  Ledger Entry 2 (CREDIT)    │
│  Amount: 350.0000           │                         │  Amount: 350.0000           │
│  @Immutable (DB Trigger)    │                         │  @Immutable (DB Trigger)    │
└─────────────────────────────┴────────────┬────────────┴─────────────────────────────┘
                                           │
                        Σ(DEBIT) + Σ(CREDIT) = 0.0000
                           (Zero-Sum Conservation)
```

### 1. Double-Entry Bookkeeping & Zero-Sum Invariant
Every financial transaction between Account A and Account B is executed atomically inside a single database transaction:
- **Debit Entry**: Decrements the source account balance.
- **Credit Entry**: Increments the destination account balance.
- **Zero-Sum Balance**: Within any transfer, `Σ(DEBIT) + Σ(CREDIT) == 0`. Money is strictly conserved across the entire system.
- **Continuous Reconciliation**: Every account's cached `balance` column can be independently reconciled at any time against the sum of its immutable ledger rows:
  $$\text{balance} = \sum (\text{CREDIT}) - \sum (\text{DEBIT})$$

### 2. Append-Only Immutability
- Hibernate entities `Transaction` and `LedgerEntry` are annotated with `@org.hibernate.annotations.Immutable` and designed without mutating setters.
- Database-level PostgreSQL triggers (`trg_immutable_ledger_entries` and `trg_immutable_transactions`) unconditionally intercept and raise exceptions on any SQL `UPDATE` or `DELETE` statements. Once written, financial records can never be modified.

### 3. Distributed Idempotency Control
- Every transfer requires an `Idempotency-Key` (passed either via the `Idempotency-Key` HTTP header or within the JSON payload).
- The system enforces a unique database constraint (`uq_transactions_idempotency_key`).
- If an idempotency key is replayed:
  - Pre-check looks up the existing transaction and returns the original transfer response immediately with `idempotentReplay: true`.
  - Under ultra-high concurrency where two identical keys hit within the exact same millisecond, the PostgreSQL unique constraint catches the race condition, rolls back the redundant attempt, and re-queries the committed transaction without duplicate entries.

### 4. High-Contention Concurrency & Deadlock Prevention
- **Deterministic Account Sorting**: Symmetrical transfers (e.g. A $\to$ B and B $\to$ A happening at the same millisecond) could produce circular database deadlocks. The `TransferExecutor` deterministically sorts the accounts by `UUID` prior to applying updates, converting circular contention into serial execution.
- **Optimistic Locking with Exponential Jittered Backoff**: The `Account` entity uses JPA `@Version`. When multiple concurrent threads contend for the same account, JPA throws `ObjectOptimisticLockingFailureException`. The `LedgerService` catches this failure and enters a backoff retry loop:
  $$\text{backoffMs} = \min\left(10 \cdot 1.6^{\text{attempt}} + \text{randomJitter}(5, 25), 1500\right)$$
  Each retry executes within a fresh `Propagation.REQUIRES_NEW` transaction boundary.

---

## 📂 Project Structure

```
payment-ledger-service/
├── .mvn/wrapper/
│   └── maven-wrapper.properties
├── docker-compose.yml
├── mvnw / mvnw.cmd
├── pom.xml
├── README.md
├── LICENSE
└── src/
    ├── main/
    │   ├── java/com/ledger/
    │   │   ├── PaymentLedgerApplication.java
    │   │   ├── api/
    │   │   │   ├── controller/
    │   │   │   │   └── LedgerController.java
    │   │   │   ├── dto/
    │   │   │   │   ├── AccountResponse.java
    │   │   │   │   ├── CreateAccountRequest.java
    │   │   │   │   ├── LedgerEntryDto.java
    │   │   │   │   ├── TransferRequest.java
    │   │   │   │   └── TransferResponse.java
    │   │   │   └── exception/
    │   │   │       └── GlobalExceptionHandler.java
    │   │   ├── domain/
    │   │   │   ├── exception/
    │   │   │   │   ├── AccountNotFoundException.java
    │   │   │   │   ├── CurrencyMismatchException.java
    │   │   │   │   ├── IdempotencyConflictException.java
    │   │   │   │   ├── InsufficientFundsException.java
    │   │   │   │   └── SelfTransferException.java
    │   │   │   ├── model/
    │   │   │   │   ├── Account.java
    │   │   │   │   ├── EntryType.java
    │   │   │   │   ├── LedgerEntry.java
    │   │   │   │   ├── Transaction.java
    │   │   │   │   └── TransactionStatus.java
    │   │   │   └── repository/
    │   │   │       ├── AccountRepository.java
    │   │   │       ├── LedgerEntryRepository.java
    │   │   │       └── TransactionRepository.java
    │   │   └── service/
    │   │       ├── AccountService.java
    │   │       ├── LedgerService.java
    │   │       └── TransferExecutor.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__init_ledger_schema.sql
    └── test/
        ├── java/com/ledger/
        │   ├── BaseIntegrationTest.java
        │   ├── AccountIntegrationTest.java
        │   ├── TransferIntegrationTest.java
        │   └── LedgerConcurrencyProofTest.java
        └── resources/
            └── application-test.yml
```

---

## 🗄️ Database Schema & Flyway Migrations

The database schema (`V1__init_ledger_schema.sql`) enforces financial constraints and append-only immutability at the engine level:

```sql
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Append-Only Immutability Triggers
CREATE OR REPLACE FUNCTION prevent_immutable_record_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Modifications (UPDATE or DELETE) are strictly prohibited on immutable ledger table %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_record_modification();

CREATE TRIGGER trg_immutable_transactions
BEFORE UPDATE OR DELETE ON transactions
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_record_modification();
```

---

## 🚀 Running Locally

### Prerequisites
- **Java 21**
- **Docker & Docker Compose** (for local PostgreSQL instance or Testcontainers)

### 1. Start Local PostgreSQL via Docker Compose
```bash
docker compose up -d
```
This spins up PostgreSQL 16 on port `5432` with database `ledger_db`, user `ledger_user`, and password `ledger_password`.

### 2. Run the Application
```bash
# Using Maven Wrapper (Windows)
mvnw.cmd spring-boot:run

# Using Maven Wrapper (macOS / Linux)
./mvnw spring-boot:run
```

The service will boot on `http://localhost:8080`. Flyway automatically runs database migrations on startup.

---

## 📡 REST API Reference

### 1. Create an Account
```http
POST /api/v1/accounts
Content-Type: application/json

{
  "name": "Treasury Primary",
  "currency": "USD",
  "initialBalance": 10000.0000
}
```
**Response (201 Created):**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Treasury Primary",
  "currency": "USD",
  "balance": 10000.0000,
  "calculatedLedgerSum": 10000.0000,
  "reconciled": true,
  "version": 0,
  "createdAt": "2026-10-14T18:00:00Z",
  "updatedAt": "2026-10-14T18:00:00Z"
}
```

### 2. Retrieve Account Details & Balance Reconciliation
```http
GET /api/v1/accounts/3fa85f64-5717-4562-b3fc-2c963f66afa6
```
**Response (200 OK):**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Treasury Primary",
  "currency": "USD",
  "balance": 10000.0000,
  "calculatedLedgerSum": 10000.0000,
  "reconciled": true,
  "version": 0,
  "createdAt": "2026-10-14T18:00:00Z",
  "updatedAt": "2026-10-14T18:00:00Z"
}
```

### 3. Execute Double-Entry Transfer
```http
POST /api/v1/transfers
Idempotency-Key: req-tx-883921
Content-Type: application/json

{
  "sourceAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "destinationAccountId": "7b8e5c41-92e1-4c55-b461-9f798e21f7c3",
  "amount": 250.0000,
  "currency": "USD",
  "description": "Invoice payment #4092"
}
```
**Response (201 Created):**
```json
{
  "transactionId": "e9324c57-6110-4bf6-94e8-8a8f192b001a",
  "idempotencyKey": "req-tx-883921",
  "sourceAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "destinationAccountId": "7b8e5c41-92e1-4c55-b461-9f798e21f7c3",
  "amount": 250.0000,
  "currency": "USD",
  "status": "COMPLETED",
  "description": "Invoice payment #4092",
  "createdAt": "2026-10-14T18:05:00Z",
  "idempotentReplay": false,
  "entries": [
    {
      "id": "0b48cbfa-76d3-49dc-98df-898e6efbb011",
      "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "entryType": "DEBIT",
      "amount": 250.0000,
      "createdAt": "2026-10-14T18:05:00Z"
    },
    {
      "id": "41da8ce9-0d33-4ef1-a3bb-cf77239ef138",
      "accountId": "7b8e5c41-92e1-4c55-b461-9f798e21f7c3",
      "entryType": "CREDIT",
      "amount": 250.0000,
      "createdAt": "2026-10-14T18:05:00Z"
    }
  ]
}
```

*Note: Resubmitting the same `Idempotency-Key` returns HTTP 200 OK with `"idempotentReplay": true` and creates zero new rows.*

---

## 🧪 Testing with Testcontainers

Integration tests spin up a disposable PostgreSQL container automatically via Testcontainers. No manual database setup is required.

```bash
# Run all unit, integration, and stress tests
mvn clean test
```

---

## ⚡ Concurrency Proof (1,000 Simultaneous Transfers)

The test suite includes a dedicated stress test in [`LedgerConcurrencyProofTest.java`](src/test/java/com/ledger/LedgerConcurrencyProofTest.java).

### Test Design
1. **Accounts Initialized**: 5 accounts initialized with **$10,000.0000** each $\implies$ **$50,000.0000** initial total system money pool.
2. **Workload**:
   - **1,000 unique transfer requests** between randomized account pairs with randomized amounts ($1.0000 to $25.0000).
   - **100 intentional duplicate submissions** re-using existing idempotency keys interspersed concurrently.
   - **Total of 1,100 tasks** submitted to a 64-thread `ExecutorService`.
3. **Synchronization**: A `CountDownLatch startGun` ensures all 64 threads hit the database and optimistic locking mechanisms at the exact same millisecond.
4. **Completion Barrier**: A `CountDownLatch completionLatch` tracks completion across all 1,100 requests.

### Invariants Verified
| Invariant | Description | Verification Method | Result |
| :--- | :--- | :--- | :--- |
| **Invariant 1: Total Money Conservation** | Total pool across all accounts is strictly conserved | $\sum \text{Balance}_{\text{final}} == \$50,000.0000$ | **Zero-Sum (0.0000 drift)** |
| **Invariant 2: Continuous Reconciliation** | Every account balance strictly matches its ledger entries | $\text{Balance}_i == \sum \text{CREDIT}_i - \sum \text{DEBIT}_i$ | **100% Reconciled** |
| **Invariant 3: Append-Only & Idempotency** | Duplicate requests create 0 new entries | Total new ledger rows $== 2 \times 1,000 = 2,000$ | **Exact match (2,000 rows)** |

### Sample Test Output Log
```text
==========================================================================
STARTING CONCURRENCY PROOF: 1,000 SIMULTANEOUS TRANSFERS
Accounts: 5, Initial Balance Per Account: 10000.0000, Total System Pool: 50000.0000
==========================================================================
... [Optimistic locking collisions gracefully retried via exponential backoff] ...
==========================================================================
CONCURRENCY TEST COMPLETED in 3412 ms (3 sec)
Total Requests Executed: 1100
Successful New Transfers: 1000
Idempotent Replays Caught: 100
Failures / Errors: 0
Throughput: 322.39 transfers/second
==========================================================================
--- AUDIT: ACCOUNT BALANCE BREAKDOWN ---
Account #0: ID=58e46950-..., Balance=9894.2100, Net Ledger Sum=9894.2100, Version=412
Account #1: ID=1c238b90-..., Balance=10142.8300, Net Ledger Sum=10142.8300, Version=398
Account #2: ID=e8371a14-..., Balance=9978.5000, Net Ledger Sum=9978.5000, Version=406
Account #3: ID=bf190e29-..., Balance=10034.1200, Net Ledger Sum=10034.1200, Version=389
Account #4: ID=49f8cd62-..., Balance=9950.3400, Net Ledger Sum=9950.3400, Version=395
System Initial Pool: 50000.0000, Final System Pool: 50000.0000, Variance: 0.0000
New Transactions Created: 1000 (Expected: 1000)
New Ledger Entries Created: 2000 (Expected: 2000)
==========================================================================
CONCURRENCY PROOF PASSED: ZERO BALANCE DRIFT VERIFIED!
==========================================================================
```

---

## 🛡️ License

This project is licensed under the [MIT License](LICENSE).
