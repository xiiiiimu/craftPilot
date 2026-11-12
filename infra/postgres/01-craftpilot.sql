\getenv app_user CRAFT_DB_USER
\getenv app_password CRAFT_DB_PASSWORD
\getenv app_database POSTGRES_DB

CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
ALTER DATABASE :"app_database" OWNER TO :"app_user";
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";

CREATE SCHEMA IF NOT EXISTS craftpilot_app AUTHORIZATION :"app_user";
ALTER ROLE :"app_user" IN DATABASE :"app_database" SET search_path TO craftpilot_app, public;
