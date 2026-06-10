# Deploy on AWS (ECS Express Mode)

ECS Express Mode provisions a Fargate service + internet-facing ALB with HTTPS at `https://<service-name>.ecs.<region>.on.aws`. The [Dockerfile](../../Dockerfile) is self-building — no local build step.

All AWS infrastructure (OIDC deploy role, the two ECS roles, ECR, RDS, and a Secrets Manager secret) is one CloudFormation stack: [`infra.yaml`](infra.yaml). Deploys then push to ECR and roll the service via GitHub Actions ([`deploy-aws.yml`](../../.github/workflows/deploy-aws.yml)), triggered manually (`workflow_dispatch`).

---

## 1 · Create the infrastructure stack

Deploy `infra.yaml` once, in the region you want. Either via the console (**CloudFormation → Create stack → upload `infra.yaml`**) or the CLI:

```bash
aws cloudformation deploy \
  --stack-name resume-scope \
  --region <REGION> \
  --template-file deploy/aws/infra.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    VpcId=<DEFAULT_VPC_ID> \
    SubnetIds=<SUBNET_A>,<SUBNET_B> \
    DbPassword='<STRONG_DB_PASSWORD>' \
    OpenAiApiKey='<OPENAI_API_KEY>' \
    ApiKey='<LONG_RANDOM_SECRET>'
```

Parameters:
- **VpcId / SubnetIds** — your default VPC and two of its subnets (different AZs). The console gives dropdowns.
- **VpcCidr** — defaults to `172.31.0.0/16` (the default-VPC range); change only if yours differs.
- **DbPassword / OpenAiApiKey / ApiKey** — secrets (hidden in console/events). `ApiKey` is the shared secret clients send as `X-API-Key` on `/api/**`.
- **CreateOidcProvider** — defaults to `Yes`. Set to `No` if your account already has a GitHub OIDC provider (an account allows only one).

The stack creates `db.t4g.micro` Postgres (free-tier eligible), the IAM roles, the ECR repo, and the secret. RDS takes ~5 min.

## 2 · Wire GitHub

Create a GitHub **Environment** named `aws` (Settings → Environments), then copy the stack **Outputs** straight in:

| From stack output | Put in GitHub as | Kind |
|---|---|---|
| `DeployRoleArn` | `AWS_DEPLOY_ROLE_ARN` | secret |
| `ExecutionRoleArn` | `ECS_EXECUTION_ROLE_ARN` | secret |
| `InfrastructureRoleArn` | `ECS_INFRASTRUCTURE_ROLE_ARN` | secret |
| `RdsEndpoint` | `RDS_ENDPOINT` | variable |
| `AppSecretArn` | `APP_SECRET_ARN` | variable |
| *(the region you used)* | `AWS_REGION` | variable |

## 3 · Deploy

Actions → **Deploy · AWS (ECS Express)** → **Run workflow**. The first run creates the service + ALB and runs Flyway on startup; later runs just roll the image.

Public URL is `https://resume-scope.ecs.<REGION>.on.aws`:

```bash
curl https://resume-scope.ecs.<REGION>.on.aws/health
curl -H "X-API-Key: <API_KEY>" https://resume-scope.ecs.<REGION>.on.aws/api/job-roles
```

---

## Teardown

Delete the stack to remove everything (it also empties + removes the ECR repo and the RDS instance):

```bash
aws cloudformation delete-stack --stack-name resume-scope --region <REGION>
```
