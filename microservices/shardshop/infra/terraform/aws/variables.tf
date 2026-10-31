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
  description = "Public CIDR blocks that can reach the EKS API endpoint, for example [\"203.0.113.10/32\"]."
  type        = list(string)

  validation {
    condition     = length(var.api_allowed_cidrs) > 0 && !contains(var.api_allowed_cidrs, "0.0.0.0/0")
    error_message = "Give at least one CIDR block, and do not open the API endpoint to 0.0.0.0/0."
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
  description = "Graviton (arm64) instance type of the workers. The images are linux/arm64 only. The default is enough for the system pods only."
  type        = string
  default     = "t4g.medium"
}

variable "nodes_per_zone" {
  description = "Number of workers in each of the three zones."
  type        = number
  default     = 1
}
