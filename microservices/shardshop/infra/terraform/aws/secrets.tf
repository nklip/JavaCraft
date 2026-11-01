# The runtime secrets of the database roles and the broker. The passwords are ephemeral
# and the secret values are write-only, thus no password enters the Terraform state.
# A destroy deletes the secrets without a recovery window.
locals {
  database_roles   = toset(local.runtime_secrets.databaseRoles)
  broker_passwords = toset(["admin", "order", "ledger", "erlang_cookie"])
}

ephemeral "aws_secretsmanager_random_password" "database" {
  for_each = local.database_roles

  password_length     = 40
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret" "database" {
  for_each = local.database_roles

  name                    = "${local.runtime_secrets.prefix}${replace(each.key, "_", "-")}"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "database" {
  for_each = local.database_roles

  secret_id                = aws_secretsmanager_secret.database[each.key].id
  secret_string_wo_version = 1
  secret_string_wo = jsonencode({
    username = each.key
    password = ephemeral.aws_secretsmanager_random_password.database[each.key].random_password
  })
}

ephemeral "aws_secretsmanager_random_password" "broker" {
  for_each = local.broker_passwords

  password_length     = 40
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret" "broker" {
  name                    = "${local.runtime_secrets.prefix}rabbitmq"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "broker" {
  secret_id                = aws_secretsmanager_secret.broker.id
  secret_string_wo_version = 1
  secret_string_wo = jsonencode({
    admin_password  = ephemeral.aws_secretsmanager_random_password.broker["admin"].random_password
    order_password  = ephemeral.aws_secretsmanager_random_password.broker["order"].random_password
    ledger_password = ephemeral.aws_secretsmanager_random_password.broker["ledger"].random_password
    erlang_cookie   = ephemeral.aws_secretsmanager_random_password.broker["erlang_cookie"].random_password
    # The same users and permissions as infra/helm/shardshop/files/rabbitmq/users.jq of kind.
    users_json = jsonencode({
      vhosts = [{ name = "shardshop" }]
      users = [
        { name = "shardshop-admin", password = ephemeral.aws_secretsmanager_random_password.broker["admin"].random_password, tags = ["administrator"] },
        { name = "shardshop-order", password = ephemeral.aws_secretsmanager_random_password.broker["order"].random_password, tags = [] },
        { name = "shardshop-ledger", password = ephemeral.aws_secretsmanager_random_password.broker["ledger"].random_password, tags = [] },
      ]
      permissions = [
        { user = "shardshop-admin", vhost = "shardshop", configure = ".*", write = ".*", read = ".*" },
        { user = "shardshop-order", vhost = "shardshop", configure = "^$", write = "^ledger\\.commands$", read = "^order\\.ledger-results$" },
        { user = "shardshop-ledger", vhost = "shardshop", configure = "^$", write = "^order\\.saga-results$", read = "^ledger\\.record-order$" },
      ]
    })
  })
}
