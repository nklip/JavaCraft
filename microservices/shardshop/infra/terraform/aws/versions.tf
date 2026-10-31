terraform {
  # use_lockfile (S3 native state lock) needs Terraform 1.11 or newer.
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.71.0"
    }
  }

  # The bucket comes from the state stack: terraform init -backend-config="bucket=...".
  backend "s3" {
    key          = "shardshop/aws/terraform.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}
