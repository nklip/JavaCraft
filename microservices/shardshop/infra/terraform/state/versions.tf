terraform {
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.71.0"
    }
  }

  # This stack creates the remote state bucket, thus its own state stays local
  # (terraform.tfstate in this folder, ignored by Git).
}
