output "hostname" {
  value = local.hostname
}

output "distribution_id" {
  value = aws_cloudfront_distribution.webpages.id
}

output "user_pool_id" {
  value = aws_cognito_user_pool.webpages.id
}

# The access values of the webpages layer of the shared chart.
output "access" {
  value = {
    hostname = local.hostname
    issuer   = "https://${aws_cognito_user_pool.webpages.endpoint}"
    clientId = aws_cognito_user_pool_client.webpages.id
  }
}

output "client_secret" {
  value     = aws_cognito_user_pool_client.webpages.client_secret
  sensitive = true
}

# Signs out of Cognito, then of the webpages. A different user can then sign in.
output "sign_out_url" {
  value = "https://${aws_cognito_user_pool_domain.webpages.domain}.auth.${var.region}.amazoncognito.com/logout?client_id=${aws_cognito_user_pool_client.webpages.id}&logout_uri=${urlencode("https://${local.hostname}/logout")}"
}
