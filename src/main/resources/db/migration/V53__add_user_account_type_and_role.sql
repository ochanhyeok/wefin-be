ALTER TABLE users
    ADD COLUMN account_type VARCHAR(20);

UPDATE users
SET account_type = 'NORMAL'
WHERE account_type IS NULL;

ALTER TABLE users
    ALTER COLUMN account_type SET DEFAULT 'NORMAL';

ALTER TABLE users
    ALTER COLUMN account_type SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_account_type
        CHECK (account_type IN ('NORMAL', 'CONTEST', 'BUSINESS'));

ALTER TABLE users
    ADD COLUMN role VARCHAR(20);

UPDATE users
SET role = 'USER'
WHERE role IS NULL;

ALTER TABLE users
    ALTER COLUMN role SET DEFAULT 'USER';

ALTER TABLE users
    ALTER COLUMN role SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_role
        CHECK (role IN ('USER', 'ADMIN'));
