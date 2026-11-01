-- Only for the delivery drill of the AWS deployment. The sleep gives the drill time to
-- interrupt the migration. It stays below the statement timeout of the migration role.
SELECT pg_sleep(12);
