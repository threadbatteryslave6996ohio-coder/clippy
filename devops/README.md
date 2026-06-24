# Clippy Azure PostgreSQL

Terraform for an Azure Database for PostgreSQL Flexible Server sized for Azure's free-account PostgreSQL allowance: Burstable B1MS with 32 GB storage.

Azure free eligibility depends on your subscription offer, region, usage, and current Microsoft terms. Check the Azure portal cost estimate before applying.

## Deploy

```bash
cd ~/Desktop/clippy/devops
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` and set:

- `postgres_admin_password`
- `allowed_ip_addresses`

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

## Destroy

```bash
cd ~/Desktop/clippy/devops
terraform destroy
```
