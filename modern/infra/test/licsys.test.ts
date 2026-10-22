import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { QUEUES, SERVICES } from '../lib/config';
import { LicsysStack } from '../lib/licsys-stack';

const template = Template.fromStack(new LicsysStack(new App(), 'LicsysNext', { env: { region: 'ap-southeast-2' }, imageTag: 'test' }));
const resources = (type: string) => template.findResources(type);

describe('contract: modern/ARCHITECTURE.md', () => {
  test('event bus licsys-bus', () => {
    template.hasResourceProperties('AWS::Events::EventBus', { Name: 'licsys-bus' });
  });

  test.each([
    ['licence-drivers', 'driverId'],
    ['demerit-ledger', 'driverId'],
    ['registration-renewals', 'renewalId'],
    ['notification-activity', 'eventId'],
  ])('DynamoDB table %s (pk %s)', (name, pk) => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      TableName: name,
      KeySchema: [{ AttributeName: pk, KeyType: 'HASH' }],
      BillingMode: 'PAY_PER_REQUEST',
      PointInTimeRecoverySpecification: { PointInTimeRecoveryEnabled: true },
    });
    template.resourceCountIs('AWS::DynamoDB::Table', 4);
  });

  test.each([
    ['licence-inbox', { 'detail-type': ['DemeritPointsChanged'] }],
    ['demerit-inbox', { 'detail-type': ['DriverRegistered', 'LicenceProgressed'] }],
    ['notification-inbox', { 'detail-type': ['SuspensionThresholdReached', 'LicenceProgressed'] }],
    ['activity-inbox', { source: [{ prefix: 'licsys.' }] }],
  ])('rule to-%s routes exactly %j into its queue, with a DLQ', (queue, pattern) => {
    template.hasResourceProperties('AWS::Events::Rule', {
      Name: `to-${queue}`,
      EventBusName: Match.anyValue(),
      EventPattern: pattern,
      Targets: [Match.objectLike({ Arn: { 'Fn::GetAtt': [Match.stringLikeRegexp(queue.replace(/-/g, '')), 'Arn'] }, DeadLetterConfig: Match.anyValue() })],
    });
    template.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: queue,
      RedrivePolicy: { deadLetterTargetArn: Match.anyValue(), maxReceiveCount: 5 },
    });
    template.hasResourceProperties('AWS::SQS::Queue', { QueueName: `${queue}-dlq` });
  });

  test('exactly the four contract rules on the bus', () => {
    expect(Object.keys(resources('AWS::Events::Rule'))).toHaveLength(QUEUES.length);
  });
});

describe('compute and edge', () => {
  test.each(SERVICES.map((s) => [s.name, s.port]))('Fargate service %s on port %d behind the ALB', (name, port) => {
    template.hasResourceProperties('AWS::ECS::Service', {
      ServiceName: name,
      LaunchType: 'FARGATE',
      DeploymentConfiguration: Match.objectLike({ DeploymentCircuitBreaker: { Enable: true, Rollback: true } }),
      LoadBalancers: [Match.objectLike({ ContainerName: name, ContainerPort: port })],
    });
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      Port: port,
      HealthCheckPath: '/actuator/health',
    });
  });

  test('ALB is internal and only reachable from the VPC link and the progression trigger', () => {
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', { Scheme: 'internal' });
    const albSgIngress = Object.values(resources('AWS::EC2::SecurityGroupIngress'))
      .filter((r: any) => r.Properties.FromPort === 80);
    expect(albSgIngress).toHaveLength(2);
    albSgIngress.forEach((r: any) => expect(r.Properties.CidrIp).toBeUndefined());
  });

  test('demerit routes win over licence /api/drivers/*', () => {
    const rules = Object.values(resources('AWS::ElasticLoadBalancingV2::ListenerRule')) as any[];
    const prio = (pattern: string) => rules.find((r) => r.Properties.Conditions[0].PathPatternConfig.Values.includes(pattern)).Properties.Priority;
    expect(prio('/api/drivers/*/demerits')).toBeLessThan(prio('/api/drivers/*'));
  });

  test('HTTP API uses a VPC link and a Cognito JWT authorizer on every route', () => {
    template.hasResourceProperties('AWS::ApiGatewayV2::Integration', { ConnectionType: 'VPC_LINK', IntegrationType: 'HTTP_PROXY' });
    template.hasResourceProperties('AWS::ApiGatewayV2::Authorizer', { AuthorizerType: 'JWT' });
    const routes = Object.values(resources('AWS::ApiGatewayV2::Route')) as any[];
    expect(routes.length).toBeGreaterThan(0);
    routes.forEach((r) => expect(r.Properties.AuthorizationType).toBe('JWT'));
    expect(routes.some((r) => String(r.Properties.RouteKey).includes('/api/admin'))).toBe(false);
  });

  test('Cognito user pool: no self sign-up, MFA required', () => {
    template.hasResourceProperties('AWS::Cognito::UserPool', {
      AdminCreateUserConfig: { AllowAdminCreateUserOnly: true },
      MfaConfiguration: 'ON',
    });
  });
});

describe('nightly progression replaces LICNIGHT STEP020', () => {
  test('EventBridge Scheduler runs nightly in Australia/Sydney with retries and a DLQ', () => {
    template.hasResourceProperties('AWS::Scheduler::Schedule', {
      ScheduleExpression: 'cron(0 2 * * ? *)',
      ScheduleExpressionTimezone: 'Australia/Sydney',
      Target: Match.objectLike({
        RetryPolicy: { MaximumRetryAttempts: 3, MaximumEventAgeInSeconds: 3600 },
        DeadLetterConfig: Match.anyValue(),
      }),
    });
  });

  test('trigger Lambda calls POST /api/progression/run on the internal ALB', () => {
    template.hasResourceProperties('AWS::Lambda::Function', {
      FunctionName: 'licsys-progression-trigger',
      Environment: { Variables: Match.objectLike({ RUN_PATH: '/api/progression/run', RUN_TIMEZONE: 'Australia/Sydney' }) },
      VpcConfig: Match.anyValue(),
    });
  });
});

describe('least-privilege IAM', () => {
  const policies = Object.entries(resources('AWS::IAM::Policy')) as [string, any][];
  const statementsFor = (rolePrefix: string) => {
    const p = policies.find(([id]) => id.startsWith(`${rolePrefix}DefaultPolicy`));
    expect(p).toBeDefined();
    return p![1].Properties.PolicyDocument.Statement as any[];
  };

  test.each(SERVICES.map((s) => [s.id, s]))('%s task role: own table, own event source, own queues only', (_id, svc) => {
    const stmts = statementsFor(`${svc.id}TaskRole`);
    const json = JSON.stringify(stmts);
    for (const other of SERVICES.filter((o) => o.id !== svc.id)) {
      expect(json).not.toContain(`${other.id}Table`);
    }
    const put = stmts.find((s) => [s.Action].flat().includes('events:PutEvents'));
    expect(put.Condition).toEqual({ StringEquals: { 'events:source': svc.eventSource } });
    const consume = stmts.filter((s) => [s.Action].flat().includes('sqs:ReceiveMessage'));
    const queueRefs = consume.flatMap((s) => [s.Resource].flat()).map((r: any) => r['Fn::GetAtt'][0]);
    expect(queueRefs.length).toBe(svc.consumes.length);
    stmts.forEach((s) => expect([s.Resource].flat()).not.toContain('*'));
  });

  test('scheduler role can only invoke the trigger and write its DLQ', () => {
    const stmts = statementsFor('SchedulerRole');
    expect(stmts.flatMap((s) => [s.Action].flat()).sort()).toEqual(['lambda:InvokeFunction', 'sqs:GetQueueAttributes', 'sqs:GetQueueUrl', 'sqs:SendMessage'].sort());
  });
});

describe('observability', () => {
  test('dashboard and an alarm on every DLQ', () => {
    template.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    for (const q of QUEUES) {
      template.hasResourceProperties('AWS::CloudWatch::Alarm', { AlarmName: `licsys-${q.name}-dlq`, AlarmActions: Match.anyValue() });
    }
    template.hasResourceProperties('AWS::CloudWatch::Alarm', { AlarmName: 'licsys-progression-run-failed' });
  });
});

test('synthesized template snapshot', () => {
  expect(template.toJSON()).toMatchSnapshot();
});
