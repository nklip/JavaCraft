variable "region" {
  description = "AWS region of the stack. It must be the region of the S3 backend in versions.tf."
  type        = string
  default     = "eu-west-2"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes minor version. It must be inside the CNPG support matrix (CNPG 1.30: 1.34 to 1.36)."
  type        = string
  default     = "1.36"
}

variable "api_allowed_cidrs" {
  description = "Public CIDR blocks that can reach the EKS API endpoint, for example [\"203.0.113.10/32\"]. With [\"0.0.0.0/0\"], IAM authentication and the access entries are the only protection."
  type        = list(string)

  validation {
    condition     = length(var.api_allowed_cidrs) > 0 && alltrue([for cidr in var.api_allowed_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Give at least one valid CIDR block."
  }
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "nat_gateway_per_zone" {
  description = "Create one NAT gateway in each zone. The default is one shared NAT gateway."
  type        = bool
  default     = false
}

variable "node_instance_type" {
  description = "Graviton (arm64) instance type of the workers. The images are linux/arm64 only. The default holds the database tier with its backup sidecars, three brokers, a restore cluster and the add-ons, but not the applications."
  type        = string
  default     = "t4g.large"
}

variable "nodes_per_zone" {
  description = "Number of workers in each of the three zones."
  type        = number
  default     = 1
}
