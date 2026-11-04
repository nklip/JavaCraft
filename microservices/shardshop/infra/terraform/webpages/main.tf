provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "shardshop"
      Stack     = "webpages"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name = "shardshop"
  # The EKS values of the shared chart name the Cognito groups that the SecurityPolicy reads.
  webpages = yamldecode(file("${path.module}/../../helm/shardshop/eks-values.yaml")).webpages
  groups   = toset(concat(local.webpages.readGroups, local.webpages.writeGroups))
  hostname = aws_cloudfront_distribution.webpages.domain_name
}

# The AWS Load Balancer Controller creates this NLB for the Envoy Service of the Gateway
# shardshop-webpages. Thus apply this module after Argo CD has created the Gateway.
data "aws_lb" "webpages" {
  name = "${local.name}-webpages"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# All the headers, cookies and query strings of the viewer, also the Host and the Origin. Envoy
# needs them for the login cookies, the CSRF check and the page addresses.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# CloudFront connects to the internal NLB through its own network interfaces in the VPC. The NLB
# has no TLS listener, because no trusted certificate can match its name. Thus this private path
# uses HTTP.
resource "aws_cloudfront_vpc_origin" "webpages" {
  vpc_origin_endpoint_config {
    name                   = "${local.name}-webpages"
    arn                    = data.aws_lb.webpages.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# The default *.cloudfront.net name and certificate: no domain, Route 53 zone or ACM certificate.
resource "aws_cloudfront_distribution" "webpages" {
  enabled         = true
  comment         = "ShardShop webpages"
  price_class     = "PriceClass_100"
  http_version    = "http2and3"
  is_ipv6_enabled = true

  origin {
    origin_id   = "webpages"
    domain_name = data.aws_lb.webpages.dns_name

    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.webpages.id
    }
  }

  default_cache_behavior {
    target_origin_id       = "webpages"
    viewer_protocol_policy = "redirect-to-https"
    # CloudFront allows only fixed sets. Envoy answers 404 to each method that no route has.
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    compress                 = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}

# The login. Only an administrator creates users, and there is no self sign-up.
resource "aws_cognito_user_pool" "webpages" {
  name                = "${local.name}-webpages"
  user_pool_tier      = "LITE"
  deletion_protection = "INACTIVE"
  mfa_configuration   = "OFF"

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = false
    temporary_password_validity_days = 1
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }
}

# The Cognito prefix domain of the classic login page. The account ID keeps the prefix unique.
resource "aws_cognito_user_pool_domain" "webpages" {
  domain                = "${local.name}-${data.aws_caller_identity.current.account_id}"
  user_pool_id          = aws_cognito_user_pool.webpages.id
  managed_login_version = 1
}

resource "aws_cognito_user_group" "role" {
  for_each = local.groups

  name         = each.key
  user_pool_id = aws_cognito_user_pool.webpages.id
}

# One user for each group, with the name of the group. Cognito gives each user a random temporary
# password and sends no message. The operator sets the password (README), thus no password is in
# the Terraform state.
resource "aws_cognito_user" "role" {
  for_each = local.groups

  user_pool_id   = aws_cognito_user_pool.webpages.id
  username       = each.key
  message_action = "SUPPRESS"
}

resource "aws_cognito_user_in_group" "role" {
  for_each = local.groups

  user_pool_id = aws_cognito_user_pool.webpages.id
  group_name   = aws_cognito_user_group.role[each.key].name
  username     = aws_cognito_user.role[each.key].username
}

# The OIDC client of Envoy Gateway. The client secret is in the Terraform state, because Cognito
# makes it. The runbook copies it to a Kubernetes Secret.
resource "aws_cognito_user_pool_client" "webpages" {
  name                                 = "${local.name}-webpages"
  user_pool_id                         = aws_cognito_user_pool.webpages.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["https://${local.hostname}/oauth2/callback"]
  logout_urls                          = ["https://${local.hostname}/logout"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true
  id_token_validity                    = 60
  access_token_validity                = 60
  refresh_token_validity               = 12

  token_validity_units {
    id_token      = "minutes"
    access_token  = "minutes"
    refresh_token = "hours"
  }
}
