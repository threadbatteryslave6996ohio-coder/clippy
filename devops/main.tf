terraform {
  required_version = ">= 1.6.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "azurerm" {
  features {}
}

resource "random_string" "suffix" {
  length  = 6
  upper   = false
  special = false
}

resource "azurerm_resource_group" "clippy" {
  name     = "${var.name_prefix}-rg"
  location = var.location
}

resource "azurerm_postgresql_flexible_server" "clippy" {
  name                = "${var.name_prefix}-pg-${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.clippy.name
  location            = azurerm_resource_group.clippy.location

  version                = "16"
  administrator_login    = var.postgres_admin_username
  administrator_password = var.postgres_admin_password

  sku_name   = "B_Standard_B1ms"
  storage_mb = 32768

  backup_retention_days        = 7
  geo_redundant_backup_enabled = false

  public_network_access_enabled = true
}

resource "azurerm_postgresql_flexible_server_database" "clippy" {
  name      = var.postgres_database_name
  server_id = azurerm_postgresql_flexible_server.clippy.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ips" {
  for_each = var.allowed_ip_addresses

  name             = "allow-${replace(each.key, ".", "-")}"
  server_id        = azurerm_postgresql_flexible_server.clippy.id
  start_ip_address = each.value
  end_ip_address   = each.value
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ip_ranges" {
  for_each = var.allowed_ip_ranges

  name             = "allow-${each.key}"
  server_id        = azurerm_postgresql_flexible_server.clippy.id
  start_ip_address = each.value.start_ip_address
  end_ip_address   = each.value.end_ip_address
}

variable "name_prefix" {
  description = "Prefix for Azure resource names. Keep it short and lowercase."
  type        = string
  default     = "clippy"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix must be 3-22 lowercase letters, numbers, or hyphens, start with a letter, and end with a letter or number."
  }
}

variable "location" {
  description = "Azure region for the PostgreSQL Flexible Server."
  type        = string
  default     = "eastus"
}

variable "postgres_admin_username" {
  description = "Administrator username for PostgreSQL."
  type        = string
  default     = "clippyadmin"
}

variable "postgres_admin_password" {
  description = "Administrator password for PostgreSQL."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.postgres_admin_password) >= 12
    error_message = "postgres_admin_password must be at least 12 characters."
  }
}

variable "postgres_database_name" {
  description = "Database name for the Clippy server."
  type        = string
  default     = "clippy"
}

variable "allowed_ip_addresses" {
  description = "Public IPv4 addresses allowed to connect to PostgreSQL."
  type        = set(string)
  default     = []
}

variable "allowed_ip_ranges" {
  description = "Named public IPv4 ranges allowed to connect to PostgreSQL."
  type = map(object({
    start_ip_address = string
    end_ip_address   = string
  }))
  default = {}
}

output "postgres_host" {
  description = "PostgreSQL server hostname."
  value       = azurerm_postgresql_flexible_server.clippy.fqdn
}

output "spring_datasource_url" {
  description = "JDBC URL for the Clippy Spring Boot server."
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.clippy.fqdn}:5432/${azurerm_postgresql_flexible_server_database.clippy.name}?sslmode=require"
}

output "spring_datasource_username" {
  description = "Username for SPRING_DATASOURCE_USERNAME."
  value       = var.postgres_admin_username
}
