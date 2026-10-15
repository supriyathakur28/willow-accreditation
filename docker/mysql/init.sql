-- One MySQL server, two logical databases. Each has its own user, so the
-- query side physically cannot read or write the admin write model.

CREATE DATABASE IF NOT EXISTS accreditation_write CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS accreditation_read  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'acc_write'@'%' IDENTIFIED BY 'acc_write';
CREATE USER IF NOT EXISTS 'acc_read'@'%'  IDENTIFIED BY 'acc_read';

GRANT ALL PRIVILEGES ON accreditation_write.* TO 'acc_write'@'%';
GRANT ALL PRIVILEGES ON accreditation_read.*  TO 'acc_read'@'%';

FLUSH PRIVILEGES;
