CREATE DATABASE IF NOT EXISTS karrot_with_design CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS karrot_no_design CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON karrot_with_design.* TO 'karrot'@'%';
GRANT ALL PRIVILEGES ON karrot_no_design.* TO 'karrot'@'%';
