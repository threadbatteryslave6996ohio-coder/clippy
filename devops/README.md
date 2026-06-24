# Clippy Azure Infrastructure

Terraform for an Azure Database for PostgreSQL Flexible Server sized for Azure's free-account PostgreSQL allowance: Burstable B1MS with 32 GB storage.

Azure free eligibility depends on your subscription offer, region, usage, and current Microsoft terms. Check the Azure portal cost estimate before applying.

The same Terraform can optionally create an Azure Linux VM as an EC2-style server host. The VM clones this repo, builds `server`, runs it with systemd, opens the configured server port, and persists logs under `/var/log/clippy`.

## Layout

- `main.tf`: provider setup, random suffix, and shared resource group.
- `database.tf`: Azure PostgreSQL server, database, and firewall rules.
- `compute.tf`: optional server VM, networking, security group, and VM bootstrap wiring.
- `variables.tf`: module inputs.
- `outputs.tf`: module outputs.

## Deploy

```bash
cd ~/Desktop/clippy/devops
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` and set:

- `postgres_admin_password`
- `allowed_ip_addresses`

To also run the server on an Azure VM, set:

- `create_server_vm = true`
- `server_repo_url`
- `server_repo_ref`
- `vm_admin_ssh_public_key`
- `server_allowed_cidrs`
- `vm_ssh_allowed_cidrs`

Then deploy:

```bash
az login
terraform init
terraform apply
```

Use the outputs to configure the Spring Boot server:

```bash
export SPRING_DATASOURCE_URL="$(terraform output -raw spring_datasource_url)"
export SPRING_DATASOURCE_USERNAME="$(terraform output -raw spring_datasource_username)"
export SPRING_DATASOURCE_PASSWORD="<postgres_admin_password>"
```

If `create_server_vm` is enabled, use:

```bash
terraform output -raw server_url
terraform output -raw server_ssh_command
```

The VM runs these services:

- `clippy-server.service`: pulls the repo during first boot, builds the Spring Boot server, and runs it.
- `clippy-log-keeper.service`: tails `/var/log/clippy/server.log` and writes a second persisted copy to `/var/log/clippy/server-kept.log`.

Server logs are written to `/var/log/clippy/server.log` on the VM. Logrotate keeps 14 daily compressed rotations for `/var/log/clippy/*.log`.

## Destroy

```bash
cd ~/Desktop/clippy/devops
terraform destroy
```
