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

output "server_vm_public_ip" {
  description = "Public IP for the optional Clippy server VM."
  value       = var.create_server_vm ? azurerm_public_ip.server[0].ip_address : null
}

output "server_url" {
  description = "HTTP URL for the optional Clippy server VM."
  value       = var.create_server_vm ? "http://${azurerm_public_ip.server[0].ip_address}:${var.server_port}" : null
}

output "server_ssh_command" {
  description = "SSH command for the optional Clippy server VM."
  value       = var.create_server_vm ? "ssh ${var.vm_admin_username}@${azurerm_public_ip.server[0].ip_address}" : null
}
