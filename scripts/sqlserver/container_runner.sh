#!/bin/bash

# Function to cleanup container on exit
cleanup() {
    echo ""
    echo "Shutting down SQL Server container..."
    docker rm -f ojp-sqlserver
    echo "Container removed."
    exit 0
}

# Trap Ctrl+C (SIGINT) and call cleanup function
trap cleanup SIGINT SIGTERM


# Start SQL Server container
docker run --name ojp-sqlserver -e ACCEPT_EULA=Y -e SA_PASSWORD=TestPassword123! -e MSSQL_AGENT_ENABLED=true -d -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest

echo "Waiting for SQL Server to start..."
# Wait for SQL Server to be ready with health check
MAX_ATTEMPTS=60
ATTEMPT=0
until docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "SELECT 1" &> /dev/null
do
   ATTEMPT=$((ATTEMPT + 1))
   if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
       echo "SQL Server failed to start within expected time"
       exit 1
   fi
   echo "Waiting for SQL Server to be ready... (attempt $ATTEMPT/$MAX_ATTEMPTS)"
   sleep 1
done

echo "SQL Server is ready!"


# Execute SQL commands using sqlcmd inside the container
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "
CREATE DATABASE defaultdb;
"

docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "
CREATE LOGIN testuser WITH PASSWORD = 'TestPassword123!';
"

docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -d defaultdb -C -Q "
CREATE USER testuser FOR LOGIN testuser;
ALTER ROLE db_owner ADD MEMBER testuser;
"

# Install XA stored procedures for distributed transactions
echo "Installing XA stored procedures..."
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -d master -Q "
EXEC sp_sqljdbc_xa_install;
"

# Grant XA permissions to testuser login (not just database user)
# IMPORTANT: Must create user in master database and grant permissions there
echo "Granting XA permissions to testuser..."
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -d master -Q "
-- Create testuser in master database if not exists
IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'testuser')
BEGIN
   CREATE USER testuser FOR LOGIN testuser;
END

-- Grant EXECUTE permissions on all XA stored procedures
GRANT EXECUTE ON xp_sqljdbc_xa_init TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_start TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_end TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_commit TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_recover TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_forget TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_forget_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_init_ex TO testuser;

-- Create and configure the SqlJDBCXAUser role
IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R')
BEGIN
   CREATE ROLE [SqlJDBCXAUser];
END

-- Add testuser to the XA role
ALTER ROLE [SqlJDBCXAUser] ADD MEMBER testuser;
"

echo "SQL Server setup complete with XA support!"


echo ""
echo "SQL Server is running. Press Ctrl+C to stop and remove the container..."

# Wait indefinitely until Ctrl+C is pressed
while true; do
    sleep 1
done
